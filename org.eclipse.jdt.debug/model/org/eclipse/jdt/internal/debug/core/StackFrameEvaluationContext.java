package org.eclipse.jdt.internal.debug.core;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */

import com.sun.jdi.*;
import java.util.*;import org.eclipse.core.runtime.IPath;import org.eclipse.core.runtime.Path;import org.eclipse.debug.core.DebugException;import org.eclipse.jdt.core.*;import org.eclipse.jdt.core.eval.IEvaluationContext;
/**
 * An evaluation context for a stack frame.
 */

public class StackFrameEvaluationContext extends ThreadEvaluationContext {
		
	protected static final String ERROR_NO_TYPE = ERROR+ "no_type";
	protected static final String ERROR_BINARY_TYPE = ERROR+ "binary_type";
	protected static final String ERROR_INNER_TYPE = ERROR+ "inner_type";
	
	/**
	 * The stack frame context
	 */
	protected JDIStackFrame fModelFrame;
	
	/**
	 * Cache of local variables and context info - computed on each evaluation.
	 */
	protected String[] fLocalVariableTypeNames;
	protected String[] fLocalVariableNames;
	protected int[] fLocalVariableModifiers;
	protected String fDeclaringTypeName;
	protected boolean fIsStatic;
	 
	/**
	 * Constructs a context for a stack frame and IEvaluationContext
	 */
	public StackFrameEvaluationContext(JDIStackFrame modelFrame, IEvaluationContext context) {
		super((JDIThread)modelFrame.getThread(), context);
		fModelFrame = modelFrame;
	}	
			
	/**
	 * Runs the evaluation
	 */
	public void doEvaluation() throws DebugException {			
		prepare();

		IEvaluationContext context = getEvaluationContext();

		// resolve IType
		IPath path = computeElementPath();
		IJavaProject project = context.getProject();
		IType type = null;
		
		try {
			IJavaElement result = project.findElement(path);
			String[] typeNames = computeNestedTypes();
			if (result != null) {
				if (result instanceof IClassFile) {
					fModelFrame.requestFailed(ERROR_BINARY_TYPE, null);
					//type = ((IClassFile)result).getType();
				} else if (result instanceof ICompilationUnit) {
					type = ((ICompilationUnit)result).getType(typeNames[0]);
				}
			}
			for (int i = 1; i < typeNames.length; i++) {
				type = type.getType(typeNames[i]);
			}
		} catch (JavaModelException e) {
			throw new DebugException(e.getStatus());
		}
		
		if (type == null) {
			fModelFrame.requestFailed(ERROR_NO_TYPE, null);
		}
		
		if (type.getParent() instanceof IType) {
			fModelFrame.requestFailed(ERROR_INNER_TYPE, null);
		}
		
		try {
			context.evaluateCodeSnippet(fSnippet, fLocalVariableTypeNames, fLocalVariableNames, fLocalVariableModifiers, type, fIsStatic, false, this, null);
		} catch (JavaModelException e) {
			throw new DebugException(e.getStatus());
		}

	}
	
	private IPath computeElementPath() throws DebugException {
		String typeName = fModelFrame.getDeclaringTypeName();
		String sourceName = fModelFrame.getSourceName();
		if (sourceName == null) {
			int dollarIndex= typeName.indexOf('$');
			if (dollarIndex >= 0)
				typeName= typeName.substring(0, dollarIndex);
			typeName.replace('.', IPath.SEPARATOR);
			typeName+= ".java";			
		} else {
			int index = typeName.lastIndexOf('.');
			if (index >= 0) {
				typeName = typeName.substring(0, index + 1);
				typeName = typeName.replace('.', IPath.SEPARATOR);
			} else {
				typeName = "";
			}
			typeName+=sourceName;
		}

		return new Path(typeName);
	}
			
	protected String[] computeNestedTypes() throws DebugException {
		String declType = fModelFrame.getDeclaringTypeName();
		int index = declType.lastIndexOf('.');
		if (index >= 0)
			declType= declType.substring(index + 1);
		index = declType.indexOf('$');
		ArrayList list = new ArrayList(1);
		while (index >= 0) {
			list.add(declType.substring(0, index));
			declType = declType.substring(index + 1);
			index = declType.indexOf('$');
		}
		list.add(declType);
		return (String[])list.toArray(new String[list.size()]);
	}
	
	/**
	 * Evaluate the code snippet 
	 */
	protected void runSnippet(String codeSnippetClassName) throws DebugException {
		ClassType codeSnippetClass = null;
		ObjectReference codeSnippet = null;
		Method method = null;
		List arguments = null;
		ObjectReference codeSnippetRunner = null;
		VirtualMachine jdiVM = getVM();
		ThreadReference jdiThread = getUnderlyingThread();
		try {
			// Get the code snippet class
			List classes = jdiVM.classesByName(codeSnippetClassName);
			if (classes.size() == 0) {
				// Load the class
				codeSnippetClass = classForName(codeSnippetClassName);
				if (codeSnippetClass == null) {
					//XXX: throw an exception - class not found
					return;
				}
			} else {
				codeSnippetClass = (ClassType)classes.get(0);
			}

			// Create a new code snippet
			Method constructor = (Method)codeSnippetClass.methodsByName("<init>").get(0);
			codeSnippet = getModelThread().newInstance(codeSnippetClass, constructor, new ArrayList());

			// Install local variables and "this" into generated fields
			StackFrame stackFrame = getUnderlyingStackFrame();
			try {
				Iterator variables = stackFrame.visibleVariables().iterator();
				while (variables.hasNext()) {
					LocalVariable jdiVariable = (LocalVariable)variables.next();
					Value value = stackFrame.getValue(jdiVariable);
					Field field = codeSnippetClass.fieldByName(LOCAL_VAR_PREFIX + jdiVariable.name());
					codeSnippet.setValue(field, value);
				}
			} catch (AbsentInformationException e) {
				// No variables
			} catch (NativeMethodException e) {
				// No variables
			}
			
			if (!fIsStatic) {
				Field delegateThis = codeSnippetClass.fieldByName(DELEGATE_THIS);
				codeSnippet.setValue(delegateThis, stackFrame.thisObject());
			}

			// Get the method 'runCodeSnippet' and its arguments		
			method = (Method)codeSnippetClass.methodsByName(RUN_METHOD).get(0);
			arguments = new ArrayList();
		} catch (ClassNotLoadedException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		} catch (InvalidTypeException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		} catch (RuntimeException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		}

		try {
			// Invoke runCodeSnippet(CodeSnippet)
			getModelThread().invokeMethod(null, codeSnippet, method, arguments);
			
			// Retrieve values of local variables and put them back in the stack frame
			StackFrame stackFrame = getUnderlyingStackFrame();
			try {
				Iterator variables = stackFrame.visibleVariables().iterator();
				while (variables.hasNext()) {
					LocalVariable jdiVariable = (LocalVariable)variables.next();
					String typeName = getTranslatedTypeName(jdiVariable);
					if (typeName != null) {
						Field field = codeSnippetClass.fieldByName(LOCAL_VAR_PREFIX + jdiVariable.name());
						Value value = codeSnippet.getValue(field);
						stackFrame.setValue(jdiVariable, value);
					}
				}	
			} catch (AbsentInformationException e) {
				// No variables
			} catch (NativeMethodException e) {
				// No variables
			}
			Field resultField = codeSnippetClass.fieldByName(RESULT_VALUE_FIELD);
			fResult = (ObjectReference)codeSnippet.getValue(resultField);
			Field resultTypeField = codeSnippetClass.fieldByName(RESULT_TYPE_FIELD);
			fResultType = (ClassObjectReference)codeSnippet.getValue(resultTypeField);
		} catch (ClassNotLoadedException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		} catch (InvalidTypeException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		} catch (RuntimeException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		}
	}
			
	/**
	 * Helper method - returns the top jdi stack frame on the target,
	 * based on the "model" thread.
	 */
	protected StackFrame getUnderlyingStackFrame() {
		return fModelFrame.getUnderlyingStackFrame();
	}
					
	/**
	 * Prepare for an evaluation. Retrieve local variables and declaring type info.
	 */
	protected void prepare() throws DebugException {
		
		StackFrame stackFrame = getUnderlyingStackFrame();
		try {
			List list = stackFrame.visibleVariables();
			int size = list.size();
			List typeNames = new ArrayList(size);
			List varNames = new ArrayList(size);

			for (int i = 0; i < size; i++) {
				LocalVariable jdiVariable = (LocalVariable)list.get(i);
				String typeName = getTranslatedTypeName(jdiVariable);
				if (typeName != null) {
					typeNames.add(typeName);
					varNames.add(jdiVariable.name());
				}
			}
			
			fLocalVariableTypeNames = (String[])typeNames.toArray(new String[typeNames.size()]);
			fLocalVariableNames = (String[])varNames.toArray(new String[varNames.size()]);
			fLocalVariableModifiers = new int[typeNames.size()];
			// cannot determine if local is final, so specify as default
			Arrays.fill(fLocalVariableModifiers, 0);
			
		} catch (AbsentInformationException e) {
			// No variables
			fLocalVariableTypeNames = new String[0];
			fLocalVariableNames = new String[0];
			fLocalVariableModifiers = new int[0];
		} catch (NativeMethodException e) {
			// No variables
			fLocalVariableTypeNames = new String[0];
			fLocalVariableNames = new String[0];
			fLocalVariableModifiers = new int[0];
		} catch (RuntimeException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION ,e);
		}

		try {
			Method method = stackFrame.location().method();
			fDeclaringTypeName = method.declaringType().name();
			fIsStatic = method.isStatic();
		} catch (RuntimeException e) {
			fModelFrame.targetRequestFailed(ERROR_EVALUATION, e);
		}
	}
	
	protected String getTranslatedTypeName(LocalVariable local) {
		String name = local.typeName();
		int index = name.lastIndexOf('$');
		if (index == -1) {
			return name;
		}
		if (index + 1 > name.length()) {
			// invalid name
			return name;
		}
		String last = name.substring(index + 1);
		try {
			Integer.parseInt(last);
			return null;
		} catch (NumberFormatException e) {
			return name.replace('$', '.');
		}
	}

}