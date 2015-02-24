/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.steps.scm;

import hudson.model.Label;
import hudson.plugins.git.GitSCM;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.triggers.SCMTrigger;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

public class GitStepTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File sampleRepo;

    static void git(File repo, String... cmds) throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("git");
        args.addAll(Arrays.asList(cmds));
        SubversionStepTest.run(repo, args.toArray(new String[args.size()]));
    }

    /** Otherwise {@link JenkinsRule#waitUntilNoActivity()} is ineffective when we have just pinged a commit notification endpoint. */
    @Before public void synchronousPolling() {
        r.jenkins.getDescriptorByType(SCMTrigger.DescriptorImpl.class).synchronousPolling = true;
    }

    @Before public void sampleRepo() throws Exception {
        sampleRepo = createSampleRepo(tmp);
    }

    static File createSampleRepo(TemporaryFolder tmp) throws Exception {
        File sampleRepo = tmp.newFolder();
        git(sampleRepo, "init");
        FileUtils.touch(new File(sampleRepo, "file"));
        git(sampleRepo, "add", "file");
        git(sampleRepo, "commit", "--message=init");
        return sampleRepo;
    }
    
    @Test public void basicCloneAndUpdate() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "demo");
        r.createOnlineSlave(Label.get("remote"));
        p.setDefinition(new CpsFlowDefinition(
            "node('remote') {\n" +
            "    ws {\n" +
            "        git(url: '" + sampleRepo + "', poll: false, changelog: false)\n" +
            "        sh 'for f in *; do echo PRESENT: $f; done'\n" +
            "    }\n" +
            "}"));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("Cloning the remote Git repository", b); // GitSCM.retrieveChanges
        r.assertLogContains("PRESENT: file", b);
        FileUtils.touch(new File(sampleRepo, "nextfile"));
        git(sampleRepo, "add", "nextfile");
        git(sampleRepo, "commit", "--message=next");
        b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("Fetching changes from the remote Git repository", b); // GitSCM.retrieveChanges
        r.assertLogContains("PRESENT: nextfile", b);
    }

    @Test public void changelogAndPolling() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "demo");
        p.addTrigger(new SCMTrigger("")); // no schedule, use notifyCommit only
        r.createOnlineSlave(Label.get("remote"));
        p.setDefinition(new CpsFlowDefinition(
            "node('remote') {\n" +
            "    ws {\n" +
            "        git '" + sampleRepo + "'\n" +
            "    }\n" +
            "}"));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("Cloning the remote Git repository", b);
        FileUtils.touch(new File(sampleRepo, "nextfile"));
        git(sampleRepo, "add", "nextfile");
        git(sampleRepo, "commit", "--message=next");
        System.out.println(r.createWebClient().goTo("git/notifyCommit?url=" + URLEncoder.encode(sampleRepo.getAbsolutePath(), "UTF-8"), "text/plain").getWebResponse().getContentAsString());
        r.waitUntilNoActivity();
        b = p.getLastBuild();
        assertEquals(2, b.number);
        r.assertLogContains("Fetching changes from the remote Git repository", b);
        List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = b.getChangeSets();
        assertEquals(1, changeSets.size());
        ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = changeSets.get(0);
        assertEquals(b, changeSet.getRun());
        assertEquals("git", changeSet.getKind());
        Iterator<? extends ChangeLogSet.Entry> iterator = changeSet.iterator();
        assertTrue(iterator.hasNext());
        ChangeLogSet.Entry entry = iterator.next();
        assertEquals("[nextfile]", entry.getAffectedPaths().toString());
        assertFalse(iterator.hasNext());
    }

    @Test public void multipleSCMs() throws Exception {
        File otherRepo = tmp.newFolder();
        git(otherRepo, "init");
        FileUtils.touch(new File(otherRepo, "otherfile"));
        git(otherRepo, "add", "otherfile");
        git(otherRepo, "commit", "--message=init");
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "demo");
        p.addTrigger(new SCMTrigger(""));
        p.setQuietPeriod(3); // so it only does one build
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
            "    ws {\n" +
            "        dir('main') {\n" +
            "            git(url: '" + sampleRepo + "')\n" +
            "        }\n" +
            "        dir('other') {\n" +
            "            git(url: '" + otherRepo + "')\n" +
            "        }\n" +
            "        sh 'for f in */*; do echo PRESENT: $f; done'\n" +
            "    }\n" +
            "}"));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("PRESENT: main/file", b);
        r.assertLogContains("PRESENT: other/otherfile", b);
        FileUtils.touch(new File(sampleRepo, "file2"));
        git(sampleRepo, "add", "file2");
        git(sampleRepo, "commit", "--message=file2");
        FileUtils.touch(new File(otherRepo, "otherfile2"));
        git(otherRepo, "add", "otherfile2");
        git(otherRepo, "commit", "--message=otherfile2");
        System.out.println(r.createWebClient().goTo("git/notifyCommit?url=" + URLEncoder.encode(sampleRepo.getAbsolutePath(), "UTF-8"), "text/plain").getWebResponse().getContentAsString());
        System.out.println(r.createWebClient().goTo("git/notifyCommit?url=" + URLEncoder.encode(otherRepo.getAbsolutePath(), "UTF-8"), "text/plain").getWebResponse().getContentAsString());
        r.waitUntilNoActivity();
        b = p.getLastBuild();
        assertEquals(2, b.number);
        r.assertLogContains("PRESENT: main/file2", b);
        r.assertLogContains("PRESENT: other/otherfile2", b);
        Iterator<? extends SCM> scms = p.getSCMs().iterator();
        assertTrue(scms.hasNext());
        assertEquals(sampleRepo.getAbsolutePath(), ((GitSCM) scms.next()).getRepositories().get(0).getURIs().get(0).toString());
        assertTrue(scms.hasNext());
        assertEquals(otherRepo.getAbsolutePath(), ((GitSCM) scms.next()).getRepositories().get(0).getURIs().get(0).toString());
        assertFalse(scms.hasNext());
        List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets = b.getChangeSets();
        assertEquals(2, changeSets.size());
        ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = changeSets.get(0);
        assertEquals(b, changeSet.getRun());
        assertEquals("git", changeSet.getKind());
        Iterator<? extends ChangeLogSet.Entry> iterator = changeSet.iterator();
        assertTrue(iterator.hasNext());
        ChangeLogSet.Entry entry = iterator.next();
        assertEquals("[file2]", entry.getAffectedPaths().toString());
        assertFalse(iterator.hasNext());
        changeSet = changeSets.get(1);
        iterator = changeSet.iterator();
        assertTrue(iterator.hasNext());
        entry = iterator.next();
        assertEquals("[otherfile2]", entry.getAffectedPaths().toString());
        assertFalse(iterator.hasNext());
    }

}
