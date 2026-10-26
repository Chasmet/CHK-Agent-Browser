package com.chk.agentbrowser;

import org.json.JSONObject;

/** Direct Local 3 names reuse the existing, verified native handlers. */
final class Local3Commands {
    private Local3Commands(){}
    static JSONObject translate(String action,JSONObject input)throws Exception{
        String tool,operation;
        switch(action){
            case "browser_upload_file":tool="browser";operation="upload_file";break;
            case "browser_upload_status":tool="browser";operation="upload_status";break;
            case "browser_get_page_status":tool="browser";operation="get_page_status";break;
            case "browser_fill_fields":tool="browser";operation="fill_form";break;
            case "browser_verify_fields":tool="browser";operation="verify_form";break;
            case "workflow_run":tool="tasks";operation="submit";break;
            case "workflow_status":tool="tasks";operation="status";break;
            case "workflow_resume":tool="tasks";operation="resume";break;
            case "workflow_cancel":tool="tasks";operation="cancel";break;
            default:return null;
        }
        JSONObject args=new JSONObject(input.toString()).put("tool",tool).put("operation",operation);
        return new JSONObject().put("action","desktop").put("args",args);
    }
}
