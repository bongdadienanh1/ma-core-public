/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.util.script;

import java.util.List;
import java.util.Map;

import com.serotonin.m2m2.rt.script.ScriptContextVariable;
import com.serotonin.m2m2.rt.script.ScriptLog;


/**
 * Container for script execution/validation/testing
 * @author Terry Packer
 *
 */
public class MangoJavaScript {
    private boolean wrapInFunction;  //Should this be wrapped and executed as a function i.e. meta points do this
    private String script;
    private List<ScriptContextVariable> context;
    private ScriptPermissions permissions;
    private ScriptLogLevels logLevel;
    //If non-null coerce the result into a PointValueTime with this data type
    private Integer resultDataTypeId;
    //Any additional context for the run
    private Map<String, Object> additionalContext;
    //Any test utilities without module element definitions as those are added automatically
    private List<ScriptUtility> additionalUtilities;
    //Additional log for output to logfile, output will be returned in response 
    // as a string.
    private ScriptLog log;
    private boolean closeLog;  //Is the logger to be closed after execution
    //Should we return the log output as a string in the response
    private boolean returnLogOutput = true;
    
    /**
     * @return the wrapInFunction
     */
    public boolean isWrapInFunction() {
        return wrapInFunction;
    }
    /**
     * @param wrapInFunction the wrapInFunction to set
     */
    public void setWrapInFunction(boolean wrapInFunction) {
        this.wrapInFunction = wrapInFunction;
    }
    /**
     * @return the script
     */
    public String getScript() {
        return script;
    }
    /**
     * @param script the script to set
     */
    public void setScript(String script) {
        this.script = script;
    }
    /**
     * @return the context
     */
    public List<ScriptContextVariable> getContext() {
        return context;
    }
    /**
     * @param context the context to set
     */
    public void setContext(List<ScriptContextVariable> context) {
        this.context = context;
    }
    /**
     * @return the permissions
     */
    public ScriptPermissions getPermissions() {
        return permissions;
    }
    /**
     * @param permissions the permissions to set
     */
    public void setPermissions(ScriptPermissions permissions) {
        this.permissions = permissions;
    }
    /**
     * @return the logLevel
     */
    public ScriptLogLevels getLogLevel() {
        return logLevel;
    }
    /**
     * @param logLevel the logLevel to set
     */
    public void setLogLevel(ScriptLogLevels logLevel) {
        this.logLevel = logLevel;
    }
    
    /**
     * @return the resultDataTypeId
     */
    public Integer getResultDataTypeId() {
        return resultDataTypeId;
    }
    
    /**
     * @param resultDataTypeId the resultDataTypeId to set
     */
    public void setResultDataTypeId(Integer resultDataTypeId) {
        this.resultDataTypeId = resultDataTypeId;
    }
    
    /**
     * @return the additionalContext
     */
    public Map<String, Object> getAdditionalContext() {
        return additionalContext;
    }
    /**
     * @param additionalContext the additionalContext to set
     */
    public void setAdditionalContext(Map<String, Object> additionalContext) {
        this.additionalContext = additionalContext;
    }
    /**
     * @return the additionalUtilities
     */
    public List<ScriptUtility> getAdditionalUtilities() {
        return additionalUtilities;
    }
    /**
     * @param additionalUtilites the additionalUtilities to set
     */
    public void setAdditionalUtilities(List<ScriptUtility> additionalUtilities) {
        this.additionalUtilities = additionalUtilities;
    }
    /**
     * @return the log
     */
    public ScriptLog getLog() {
        return log;
    }
    /**
     * @param log the log to set
     */
    public void setLog(ScriptLog log) {
        this.log = log;
    }
    /**
     * @return the returnLogOutput
     */
    public boolean isReturnLogOutput() {
        return returnLogOutput;
    }
    /**
     * @param returnLogOutput the returnLogOutput to set
     */
    public void setReturnLogOutput(boolean returnLogOutput) {
        this.returnLogOutput = returnLogOutput;
    }
    /**
     * @return the closeLog
     */
    public boolean isCloseLog() {
        return closeLog;
    }
    /**
     * @param closeLog the closeLog to set
     */
    public void setCloseLog(boolean closeLog) {
        this.closeLog = closeLog;
    }
}
