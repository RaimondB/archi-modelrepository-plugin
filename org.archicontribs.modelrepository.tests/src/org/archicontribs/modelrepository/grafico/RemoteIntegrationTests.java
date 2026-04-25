/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import org.archicontribs.modelrepository.GitHelper;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;

/**
 * Integration tests that simulate refresh (pull) and publish (push) scenarios
 * using a bare repository as the "remote" and local clones to represent
 * different users. This exercises the full push/fetch/merge/cross-path-detection
 * workflow that runs during Refresh Model and Publish Model actions.
 */
@SuppressWarnings("nls")
public class RemoteIntegrationTests {

    private static final String NS = "xmlns:archimate=\"http://www.archimatetool.com/archimate\"";

    @AfterEach
    public void runOnceAfterEachTest() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }

    // ========================================================================
    // Refresh (pull) scenarios
    // ========================================================================

    /**
     * Basic refresh: another user pushes changes, local user pulls.
     * Verifies that new elements from the remote appear in the local working tree.
     */
    @Test
    public void refresh_BasicPull_GetsRemoteChanges() throws Exception {
        File baseDir = new File(GitHelper.getTempTestsFolder(), "basicPull");
        File bareRepo = new File(baseDir, "remote.git");
        File localA = new File(baseDir, "userA");
        File localB = new File(baseDir, "userB");

        // Create bare repo (the "remote")
        try(Git bare = Git.init().setBare(true).setDirectory(bareRepo).call()) {
            // Clone to user A — initial setup
            try(Git gitA = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localA)
                    .call()) {
                writeGraficoModel(new File(localA, "model"));
                File bizDirA = new File(localA, "model/business");
                mkdirAndWrite(bizDirA, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
                mkdirAndWrite(new File(bizDirA, "folderX"), "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderX\" id=\"id-folderX\"/>\n");
                Files.writeString(new File(bizDirA, "folderX/BusinessActor_id-p.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"P\" id=\"id-p\"/>\n");
                writeStandardFolders(new File(localA, "model"));

                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("initial model").call();
                gitA.push().call();
            }

            // Clone to user B
            try(Git gitB = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localB)
                    .call()) {

                // User A adds a new element and pushes
                try(Git gitA = Git.open(localA)) {
                    File bizDirA = new File(localA, "model/business/folderX");
                    Files.writeString(new File(bizDirA, "BusinessRole_id-q.xml").toPath(),
                            "<archimate:BusinessRole " + NS + " name=\"Q\" id=\"id-q\"/>\n");
                    gitA.add().addFilepattern(".").call();
                    gitA.commit().setMessage("userA: add element Q").call();
                    gitA.push().call();
                }

                // User B pulls (simulates refresh)
                gitB.fetch().call();
                ObjectId oursId = gitB.getRepository().resolve("HEAD");
                ObjectId theirsId = gitB.getRepository().resolve("origin/master");
                MergeResult mergeResult = gitB.merge()
                        .include(theirsId)
                        .call();

                assertTrue(mergeResult.getMergeStatus().isSuccessful(),
                        "Merge should succeed");

                // Verify the new element arrived
                File qFile = new File(localB, "model/business/folderX/BusinessRole_id-q.xml");
                assertTrue(qFile.exists(), "Element Q should be pulled from remote");
                String content = Files.readString(qFile.toPath());
                assertTrue(content.contains("id-q"), "Q should have correct ID");
            }
        }
    }

    /**
     * Basic publish: local user makes changes and pushes to remote.
     * Verifies that another clone can see the pushed changes.
     */
    @Test
    public void publish_BasicPush_RemoteGetsChanges() throws Exception {
        File baseDir = new File(GitHelper.getTempTestsFolder(), "basicPush");
        File bareRepo = new File(baseDir, "remote.git");
        File localA = new File(baseDir, "userA");
        File localB = new File(baseDir, "userB");

        try(Git bare = Git.init().setBare(true).setDirectory(bareRepo).call()) {
            // Clone to user A, create initial model, push
            try(Git gitA = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localA)
                    .call()) {
                writeGraficoModel(new File(localA, "model"));
                File bizDirA = new File(localA, "model/business");
                mkdirAndWrite(bizDirA, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
                writeStandardFolders(new File(localA, "model"));

                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("initial model").call();
                gitA.push().call();

                // User A adds elements and pushes (simulates publish)
                File folderX = new File(bizDirA, "folderX");
                mkdirAndWrite(folderX, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderX\" id=\"id-folderX\"/>\n");
                Files.writeString(new File(folderX, "BusinessActor_id-p.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"P\" id=\"id-p\"/>\n");
                Files.writeString(new File(folderX, "BusinessRole_id-q.xml").toPath(),
                        "<archimate:BusinessRole " + NS + " name=\"Q\" id=\"id-q\"/>\n");

                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("userA: add folderX with P, Q").call();
                gitA.push().call();
            }

            // Clone to user B — should see user A's changes
            try(Git gitB = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localB)
                    .call()) {

                assertTrue(new File(localB, "model/business/folderX/BusinessActor_id-p.xml").exists(),
                        "P should be at remote after publish");
                assertTrue(new File(localB, "model/business/folderX/BusinessRole_id-q.xml").exists(),
                        "Q should be at remote after publish");
            }
        }
    }

    /**
     * Refresh when remote is far ahead with folder reorganization.
     * This is the primary bug scenario: remote moves elements across folders
     * (showing as "deleted" in git diff), but cross-path detection should NOT
     * remove them because they still exist in the remote's tree at new paths.
     */
    @Test
    public void refresh_RemoteFarAhead_FolderReorg_PreservesElements() throws Exception {
        File baseDir = new File(GitHelper.getTempTestsFolder(), "farAheadReorg");
        File bareRepo = new File(baseDir, "remote.git");
        File localA = new File(baseDir, "userA");
        File localB = new File(baseDir, "userB");

        try(Git bare = Git.init().setBare(true).setDirectory(bareRepo).call()) {
            // User A: initial model with elements in folderX
            try(Git gitA = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localA)
                    .call()) {
                File modelDirA = new File(localA, "model");
                File bizDirA = new File(modelDirA, "business");
                writeGraficoModel(modelDirA);
                mkdirAndWrite(bizDirA, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
                File folderX = new File(bizDirA, "folderX");
                mkdirAndWrite(folderX, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderX\" id=\"id-folderX\"/>\n");
                for(int i = 1; i <= 5; i++) {
                    Files.writeString(new File(folderX, "BusinessActor_id-elem" + i + ".xml").toPath(),
                            "<archimate:BusinessActor " + NS + " name=\"E" + i + "\" id=\"id-elem" + i + "\"/>\n");
                }
                writeStandardFolders(modelDirA);
                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("initial: folderX with 5 elements").call();
                gitA.push().call();
            }

            // User B clones at initial state
            try(Git gitB = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localB)
                    .call()) {

                // User A makes MULTIPLE commits (simulating "far ahead"):
                // Commit 1: add new elements
                // Commit 2: move folderX → folderY
                // Commit 3: add more elements to folderY
                try(Git gitA = Git.open(localA)) {
                    File bizDirA = new File(localA, "model/business");
                    File folderX = new File(bizDirA, "folderX");

                    // Commit 1: add elements 6-8
                    for(int i = 6; i <= 8; i++) {
                        Files.writeString(new File(folderX, "BusinessActor_id-elem" + i + ".xml").toPath(),
                                "<archimate:BusinessActor " + NS + " name=\"E" + i + "\" id=\"id-elem" + i + "\"/>\n");
                    }
                    gitA.add().addFilepattern(".").call();
                    gitA.commit().setMessage("userA commit 1: add elements 6-8").call();

                    // Commit 2: move folderX → folderY
                    File folderY = new File(bizDirA, "folderY");
                    folderY.mkdirs();
                    Files.writeString(new File(folderY, "folder.xml").toPath(),
                            "<archimate:Folder " + NS + " name=\"FolderY\" id=\"id-folderX\"/>\n");
                    for(int i = 1; i <= 8; i++) {
                        File src = new File(folderX, "BusinessActor_id-elem" + i + ".xml");
                        File dst = new File(folderY, "BusinessActor_id-elem" + i + ".xml");
                        Files.copy(src.toPath(), dst.toPath());
                        src.delete();
                    }
                    new File(folderX, "folder.xml").delete();
                    folderX.delete();
                    gitA.add().addFilepattern(".").call();
                    gitA.add().addFilepattern(".").setUpdate(true).call();
                    gitA.commit().setMessage("userA commit 2: move folderX to folderY").call();

                    // Commit 3: add element 9 to folderY
                    Files.writeString(new File(folderY, "BusinessActor_id-elem9.xml").toPath(),
                            "<archimate:BusinessActor " + NS + " name=\"E9\" id=\"id-elem9\"/>\n");
                    gitA.add().addFilepattern(".").call();
                    gitA.commit().setMessage("userA commit 3: add element 9").call();

                    gitA.push().call();
                }

                // User B makes a trivial local commit (so merge is not FF)
                Files.writeString(new File(localB, "model/folder.xml").toPath(),
                        "<archimate:ArchimateModel " + NS
                        + " name=\"Test Local\" id=\"id-model\" version=\"5.0.0\"/>\n");
                gitB.add().addFilepattern(".").call();
                gitB.commit().setMessage("userB: trivial update").call();

                // User B fetches and merges (simulates refresh)
                gitB.fetch().call();
                ObjectId oursId = gitB.getRepository().resolve("HEAD");
                ObjectId theirsId = gitB.getRepository().resolve("origin/master");

                MergeResult mergeResult = gitB.merge()
                        .include(theirsId)
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertTrue(mergeResult.getMergeStatus().isSuccessful(),
                        "Merge should succeed (no conflicts)");

                // Run cross-path deletion detection (this is what RefreshModelAction does)
                int removed = MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                        gitB.getRepository(), oursId, theirsId);

                assertEquals(0, removed,
                        "No elements should be removed — they were moved, not deleted");

                // Verify ALL elements exist at folderY
                File folderYB = new File(localB, "model/business/folderY");
                for(int i = 1; i <= 9; i++) {
                    assertTrue(new File(folderYB, "BusinessActor_id-elem" + i + ".xml").exists(),
                            "Element " + i + " should be at folderY (not falsely deleted)");
                }

                // Old location should not exist
                assertFalse(new File(localB, "model/business/folderX").exists(),
                        "folderX should not exist after merge");
            }
        }
    }

    /**
     * Refresh when remote deletes elements AND reorganizes — cross-path detection
     * should correctly remove only the truly deleted elements while preserving
     * the moved ones.
     */
    @Test
    public void refresh_RemoteDeletesAndReorganizes_OnlyDeletedRemoved() throws Exception {
        File baseDir = new File(GitHelper.getTempTestsFolder(), "deleteAndReorg");
        File bareRepo = new File(baseDir, "remote.git");
        File localA = new File(baseDir, "userA");
        File localB = new File(baseDir, "userB");

        try(Git bare = Git.init().setBare(true).setDirectory(bareRepo).call()) {
            // User A: initial model
            try(Git gitA = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localA)
                    .call()) {
                File modelDirA = new File(localA, "model");
                File bizDirA = new File(modelDirA, "business");
                writeGraficoModel(modelDirA);
                mkdirAndWrite(bizDirA, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
                File folderX = new File(bizDirA, "folderX");
                mkdirAndWrite(folderX, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderX\" id=\"id-folderX\"/>\n");
                Files.writeString(new File(folderX, "BusinessActor_id-keep1.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"Keep1\" id=\"id-keep1\"/>\n");
                Files.writeString(new File(folderX, "BusinessActor_id-keep2.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"Keep2\" id=\"id-keep2\"/>\n");
                Files.writeString(new File(folderX, "BusinessActor_id-del1.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"Del1\" id=\"id-del1\"/>\n");
                Files.writeString(new File(folderX, "BusinessActor_id-del2.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"Del2\" id=\"id-del2\"/>\n");
                writeStandardFolders(modelDirA);
                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("initial: folderX with keep1, keep2, del1, del2").call();
                gitA.push().call();
            }

            // User B clones
            try(Git gitB = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localB)
                    .call()) {

                // User A: move folder and delete some elements
                try(Git gitA = Git.open(localA)) {
                    File bizDirA = new File(localA, "model/business");
                    File folderX = new File(bizDirA, "folderX");
                    File folderY = new File(bizDirA, "folderY");

                    // Move folderX → folderY, keeping only keep1 and keep2
                    folderY.mkdirs();
                    Files.writeString(new File(folderY, "folder.xml").toPath(),
                            "<archimate:Folder " + NS + " name=\"FolderY\" id=\"id-folderX\"/>\n");
                    Files.copy(new File(folderX, "BusinessActor_id-keep1.xml").toPath(),
                            new File(folderY, "BusinessActor_id-keep1.xml").toPath());
                    Files.copy(new File(folderX, "BusinessActor_id-keep2.xml").toPath(),
                            new File(folderY, "BusinessActor_id-keep2.xml").toPath());
                    // del1 and del2 are NOT copied — they are truly deleted
                    FileUtils.deleteFolder(folderX);
                    gitA.add().addFilepattern(".").call();
                    gitA.add().addFilepattern(".").setUpdate(true).call();
                    gitA.commit().setMessage("userA: move to folderY, delete del1 and del2").call();
                    gitA.push().call();
                }

                // User B: trivial local change
                Files.writeString(new File(localB, "model/folder.xml").toPath(),
                        "<archimate:ArchimateModel " + NS
                        + " name=\"Test Local\" id=\"id-model\" version=\"5.0.0\"/>\n");
                gitB.add().addFilepattern(".").call();
                gitB.commit().setMessage("userB: trivial update").call();

                // User B refreshes
                gitB.fetch().call();
                ObjectId oursId = gitB.getRepository().resolve("HEAD");
                ObjectId theirsId = gitB.getRepository().resolve("origin/master");

                MergeResult mergeResult = gitB.merge()
                        .include(theirsId)
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertTrue(mergeResult.getMergeStatus().isSuccessful(),
                        "Merge should succeed");

                int removed = MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                        gitB.getRepository(), oursId, theirsId);

                // del1 and del2 might have leaked to working tree during merge;
                // cross-path detection should remove them
                File folderYB = new File(localB, "model/business/folderY");
                assertTrue(new File(folderYB, "BusinessActor_id-keep1.xml").exists(),
                        "keep1 should be preserved at folderY");
                assertTrue(new File(folderYB, "BusinessActor_id-keep2.xml").exists(),
                        "keep2 should be preserved at folderY");

                // del1 and del2 should NOT be at any location
                assertFalse(new File(folderYB, "BusinessActor_id-del1.xml").exists(),
                        "del1 should be removed from folderY");
                assertFalse(new File(folderYB, "BusinessActor_id-del2.xml").exists(),
                        "del2 should be removed from folderY");
                assertFalse(new File(localB, "model/business/folderX").exists(),
                        "folderX should not exist");
            }
        }
    }

    /**
     * Publish followed by refresh by another user — verify round-trip.
     * User A publishes, user B refreshes and gets the exact same model state.
     */
    @Test
    public void publishThenRefresh_RoundTrip_ModelStateMatches() throws Exception {
        File baseDir = new File(GitHelper.getTempTestsFolder(), "roundTrip");
        File bareRepo = new File(baseDir, "remote.git");
        File localA = new File(baseDir, "userA");
        File localB = new File(baseDir, "userB");

        try(Git bare = Git.init().setBare(true).setDirectory(bareRepo).call()) {
            // User A: initial model, push
            try(Git gitA = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localA)
                    .call()) {
                File modelDirA = new File(localA, "model");
                writeGraficoModel(modelDirA);
                File bizDirA = new File(modelDirA, "business");
                mkdirAndWrite(bizDirA, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
                writeStandardFolders(modelDirA);
                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("initial model").call();
                gitA.push().call();
            }

            // User B clones
            try(Git gitB = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localB)
                    .call()) {

                // User A: adds nested structure with elements (simulates publish)
                try(Git gitA = Git.open(localA)) {
                    File bizDirA = new File(localA, "model/business");
                    File folderX = new File(bizDirA, "folderX");
                    File nested = new File(folderX, "nestedFolder");
                    mkdirAndWrite(folderX, "folder.xml",
                            "<archimate:Folder " + NS + " name=\"FolderX\" id=\"id-folderX\"/>\n");
                    mkdirAndWrite(nested, "folder.xml",
                            "<archimate:Folder " + NS + " name=\"Nested\" id=\"id-nested\"/>\n");
                    Files.writeString(new File(folderX, "BusinessActor_id-a1.xml").toPath(),
                            "<archimate:BusinessActor " + NS + " name=\"A1\" id=\"id-a1\"/>\n");
                    Files.writeString(new File(nested, "BusinessRole_id-b1.xml").toPath(),
                            "<archimate:BusinessRole " + NS + " name=\"B1\" id=\"id-b1\"/>\n");

                    // Also add a relationship and a diagram reference
                    File relDir = new File(localA, "model/relations");
                    Files.writeString(new File(relDir, "AssociationRelationship_id-rel1.xml").toPath(),
                            "<archimate:AssociationRelationship " + NS
                            + " name=\"\" id=\"id-rel1\" source=\"id-a1\" target=\"id-b1\"/>\n");

                    gitA.add().addFilepattern(".").call();
                    gitA.commit().setMessage("userA: publish nested structure").call();
                    gitA.push().call();
                }

                // User B refreshes
                gitB.fetch().call();
                MergeResult mergeResult = gitB.merge()
                        .include(gitB.getRepository().resolve("origin/master"))
                        .call();

                assertTrue(mergeResult.getMergeStatus().isSuccessful(),
                        "Refresh merge should succeed");

                // Verify complete structure arrived
                assertTrue(new File(localB, "model/business/folderX/folder.xml").exists(),
                        "folderX should exist after refresh");
                assertTrue(new File(localB, "model/business/folderX/BusinessActor_id-a1.xml").exists(),
                        "A1 should exist after refresh");
                assertTrue(new File(localB, "model/business/folderX/nestedFolder/folder.xml").exists(),
                        "nestedFolder should exist after refresh");
                assertTrue(new File(localB, "model/business/folderX/nestedFolder/BusinessRole_id-b1.xml").exists(),
                        "B1 should exist after refresh");
                assertTrue(new File(localB, "model/relations/AssociationRelationship_id-rel1.xml").exists(),
                        "Relationship should exist after refresh");

                // Verify content matches what was published
                String a1Content = Files.readString(
                        new File(localB, "model/business/folderX/BusinessActor_id-a1.xml").toPath());
                assertTrue(a1Content.contains("name=\"A1\""), "A1 content should match");
                assertTrue(a1Content.contains("id=\"id-a1\""), "A1 ID should match");

                String relContent = Files.readString(
                        new File(localB, "model/relations/AssociationRelationship_id-rel1.xml").toPath());
                assertTrue(relContent.contains("source=\"id-a1\""), "Relationship source should match");
                assertTrue(relContent.contains("target=\"id-b1\""), "Relationship target should match");
            }
        }
    }

    /**
     * Concurrent publish: both users make non-conflicting changes.
     * User A pushes first. User B must refresh (fetch+merge) before pushing.
     * Verifies that both sets of changes coexist after the round-trip.
     */
    @Test
    public void concurrentPublish_NonConflicting_BothChangesPreserved() throws Exception {
        File baseDir = new File(GitHelper.getTempTestsFolder(), "concurrentPub");
        File bareRepo = new File(baseDir, "remote.git");
        File localA = new File(baseDir, "userA");
        File localB = new File(baseDir, "userB");

        try(Git bare = Git.init().setBare(true).setDirectory(bareRepo).call()) {
            // Initial model
            try(Git gitA = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localA)
                    .call()) {
                File modelDirA = new File(localA, "model");
                writeGraficoModel(modelDirA);
                File bizDirA = new File(modelDirA, "business");
                mkdirAndWrite(bizDirA, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
                mkdirAndWrite(new File(bizDirA, "folderA"), "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderA\" id=\"id-folderA\"/>\n");
                mkdirAndWrite(new File(bizDirA, "folderB"), "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderB\" id=\"id-folderB\"/>\n");
                writeStandardFolders(modelDirA);
                gitA.add().addFilepattern(".").call();
                gitA.commit().setMessage("initial model").call();
                gitA.push().call();
            }

            // Both users clone
            try(Git gitB = Git.cloneRepository()
                    .setURI(bareRepo.toURI().toString())
                    .setDirectory(localB)
                    .call()) {

                // User A adds element to folderA and pushes
                try(Git gitA = Git.open(localA)) {
                    Files.writeString(new File(localA, "model/business/folderA/BusinessActor_id-x.xml").toPath(),
                            "<archimate:BusinessActor " + NS + " name=\"X\" id=\"id-x\"/>\n");
                    gitA.add().addFilepattern(".").call();
                    gitA.commit().setMessage("userA: add X to folderA").call();
                    gitA.push().call();
                }

                // User B adds element to folderB (non-conflicting)
                Files.writeString(new File(localB, "model/business/folderB/BusinessRole_id-y.xml").toPath(),
                        "<archimate:BusinessRole " + NS + " name=\"Y\" id=\"id-y\"/>\n");
                gitB.add().addFilepattern(".").call();
                gitB.commit().setMessage("userB: add Y to folderB").call();

                // User B must refresh before pushing
                gitB.fetch().call();
                ObjectId oursId = gitB.getRepository().resolve("HEAD");
                ObjectId theirsId = gitB.getRepository().resolve("origin/master");

                MergeResult mergeResult = gitB.merge()
                        .include(theirsId)
                        .call();

                assertTrue(mergeResult.getMergeStatus().isSuccessful(),
                        "Non-conflicting merge should succeed");

                // Run cross-path detection (should find nothing to remove)
                int removed = MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                        gitB.getRepository(), oursId, theirsId);
                assertEquals(0, removed, "No cross-path deletions expected");

                // Both elements should exist
                assertTrue(new File(localB, "model/business/folderA/BusinessActor_id-x.xml").exists(),
                        "X from userA should exist after refresh");
                assertTrue(new File(localB, "model/business/folderB/BusinessRole_id-y.xml").exists(),
                        "Y from userB should exist after refresh+merge");

                // User B pushes
                gitB.push().call();

                // User A refreshes — should see Y
                try(Git gitA = Git.open(localA)) {
                    gitA.fetch().call();
                    MergeResult pullResult = gitA.merge()
                            .include(gitA.getRepository().resolve("origin/master"))
                            .call();
                    assertTrue(pullResult.getMergeStatus().isSuccessful(),
                            "UserA refresh should succeed");
                    assertTrue(new File(localA, "model/business/folderB/BusinessRole_id-y.xml").exists(),
                            "Y from userB should exist at userA after refresh");
                    assertTrue(new File(localA, "model/business/folderA/BusinessActor_id-x.xml").exists(),
                            "X from userA should still exist");
                }
            }
        }
    }

    // ========================================================================
    // Helper methods (same patterns as MergeConflictHandlerTests)
    // ========================================================================

    private void mkdirAndWrite(File dir, String fileName, String content) throws IOException {
        dir.mkdirs();
        Files.writeString(new File(dir, fileName).toPath(), content);
    }

    private void writeGraficoModel(File modelDir) throws IOException {
        mkdirAndWrite(modelDir, "folder.xml",
                "<archimate:ArchimateModel " + NS
                + " name=\"Test\" id=\"id-model\" version=\"5.0.0\"/>\n");
    }

    private void writeStandardFolders(File modelDir) throws IOException {
        for(String folder : new String[]{"application", "technology", "motivation",
                "implementation_migration", "other", "strategy", "relations", "diagrams"}) {
            mkdirAndWrite(new File(modelDir, folder), "folder.xml",
                    "<archimate:Folder " + NS
                    + " name=\"" + folder + "\" id=\"id-" + folder + "\" type=\"" + folder + "\"/>\n");
        }
    }
}
