package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;

/**
 * @author Vivek Pandey
 */
@Extension
public class BuildQueueListener extends QueueListener {
    @Override
    public void onLeft(Queue.LeftItem li) {
        if(li.isCancelled()){
            BuildTriggerAction action = li.getAction(BuildTriggerAction.class);
            if(action != null) {
                action.getStepContext().onFailure(new Exception(String.format("Build %s was cancelled.", li.task.getName())));
            }
        }
    }


}
