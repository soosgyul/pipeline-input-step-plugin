package org.jenkinsci.plugins.workflow.steps.input;

import hudson.FilePath;
import hudson.model.Failure;
import hudson.model.FileParameterValue;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.Run;
import hudson.model.User;
import hudson.util.HttpResponses;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.inject.Inject;
import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Kohsuke Kawaguchi
 */
public class InputStepExecution extends StepExecution {
    /**
     * Pause gets added here.
     */
    @StepContextParameter
    /*package*/ transient Run run;

    /**
     * Result of the input.
     */
    private Outcome outcome;

    @Inject
    InputStep input;

    @Override
    public boolean start() throws Exception {
        // record this input
        getPauseAction().add(this);
        return false;
    }

    public String getId() {
        return input.getId();
    }

    public InputStep getInput() {
        return input;
    }

    public Run getRun() {
        return run;
    }

    /**
     * If this input step has been decided one way or the other.
     */
    public boolean isSettled() {
        return outcome!=null;
    }

    /**
     * Gets the {@link InputAction} that this step should be attached to.
     */
    private InputAction getPauseAction() {
        InputAction a = run.getAction(InputAction.class);
        if (a==null)
            run.addAction(a=new InputAction());
        return a;
    }

    /**
     * Called from the form via browser to submit/abort this input step.
     */
    @RequirePOST
    public HttpResponse doSubmit(StaplerRequest request) throws IOException, ServletException, InterruptedException {
        if (request.getParameter("proceed")!=null) {
            doProceed(request);
        } else {
            doAbort();
        }

        // go back to the Run page
        return HttpResponses.redirectTo("../../");
    }

    /**
     * REST endpoint to submit the input.
     */
    @RequirePOST
    public HttpResponse doProceed(StaplerRequest request) throws IOException, ServletException, InterruptedException {
        preSubmissionCheck();

        Object v = parseValue(request);
        outcome = new Outcome(v, null);
        context.onSuccess(v);

        postSettlement();

        // TODO: record this decision to FlowNode

        return HttpResponses.ok();
    }

    /**
     * REST endpoint to abort the workflow.
     */
    @RequirePOST
    public HttpResponse doAbort() throws IOException, ServletException {
        preSubmissionCheck();

        RejectionException e = new RejectionException(User.current());
        outcome = new Outcome(null,e);
        context.onFailure(e);

        postSettlement();

        // TODO: record this decision to FlowNode

        return HttpResponses.ok();
    }

    /**
     * Check if the current user can submit the input.
     */
    private void preSubmissionCheck() {
        if (isSettled())
            throw new Failure("This input has been already given");
        if (!input.canSubmit()) {
            throw new Failure("You need to be "+ input.getSubmitter() +" to submit this");
        }
    }

    private void postSettlement() throws IOException {
        getPauseAction().remove(this);
        run.save();
    }

    /**
     * Parse the submitted {@link ParameterValue}s
     */
    private Object parseValue(StaplerRequest request) throws ServletException, IOException, InterruptedException {
        Map<String, Object> mapResult = new HashMap<String, Object>();
        List<ParameterDefinition> defs = input.getParameters();

        Object params = request.getSubmittedForm().get("parameter");
        if (params!=null) {
            for (Object o : JSONArray.fromObject(params)) {
                JSONObject jo = (JSONObject) o;
                String name = jo.getString("name");

                ParameterDefinition d=null;
                for (ParameterDefinition def : defs) {
                    if (def.getName().equals(name))
                        d = def;
                }
                if (d == null)
                    throw new IllegalArgumentException("No such parameter definition: " + name);

                ParameterValue v = d.createValue(request, jo);
                mapResult.put(name, convert(name, v));
            }
        }

        // TODO: perhaps we should return a different object to allow the workflow to look up
        // who approved it, etc?
        switch (mapResult.size()) {
        case 0:
            return null;    // no value if there's no parameter
        case 1:
            return mapResult.values().iterator().next();
        default:
            return mapResult;
        }
    }

    private Object convert(String name, ParameterValue v) throws IOException, InterruptedException {
        if (v instanceof FileParameterValue) {
            FileParameterValue fv = (FileParameterValue) v;
            FilePath fp = new FilePath(run.getRootDir()).child(name);
            fp.copyFrom(fv.getFile());
            return fp;
        }

        // TODO: post
        try {
            Method m = v.getClass().getMethod("getValue");
            return m.invoke(v);
        } catch (NoSuchMethodException e) {
            // fall through
        } catch (IllegalAccessException e) {
            // fall through
        } catch (InvocationTargetException e) {
            throw new IOException("Failed to convert value: "+v,e);
        }

        try {
            Field f = v.getClass().getField("value");
            return f.get(v);
        } catch (IllegalAccessException e) {
            // fall through
        } catch (NoSuchFieldException e) {
            // fall through
        }

        // not sure what to do
        return null;
    }
}
