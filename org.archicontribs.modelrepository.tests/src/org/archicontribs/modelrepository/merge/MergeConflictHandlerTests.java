/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.archicontribs.modelrepository.GitHelper;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;
import com.archimatetool.model.IArchimateModel;

@SuppressWarnings("nls")
public class MergeConflictHandlerTests {

    private static final String NS = "xmlns:archimate=\"http://www.archimatetool.com/archimate\"";

    @AfterEach
    public void runOnceAfterEachTest() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }

    // ========================================================================
    // consolidateMoveGroups tests
    // ========================================================================

    /**
     * Full merge scenario: folder move + rename + element rename across branches.
     *
     * Common start:
     *   model/business/folder.xml (id-biz, "Business")
     *   model/business/folderX/folder.xml (id-folderX, "FolderX")
     *   model/business/folderX/BusinessActor_id-q.xml (name="Q")
     *
     * Branch A (from initial):
     *   - Moves folderX inside a new folder Z and renames X to Y:
     *     model/business/folderZ/folder.xml (id-folderZ, "FolderZ")
     *     model/business/folderZ/folderY/folder.xml (id-folderX, "FolderY")  ← same ID, new name+path
     *     model/business/folderZ/folderY/BusinessActor_id-q.xml (name="Q")
     *   - Removes old path completely
     *
     * Branch B (from initial):
     *   - Renames element Q to P (same id, same path, different content):
     *     model/business/folderX/BusinessActor_id-q.xml (name="P")
     *
     * Merge A into B:
     *   - Conflict on model/business/folderX/folder.xml (B has it, A deleted it)
     *   - Conflict on model/business/folderX/BusinessActor_id-q.xml (B renamed, A deleted)
     *   - model/business/folderZ/ and folderY/ auto-merged from A (no conflict)
     *
     * User choice: Keep New (THEIRS = branch A's structure), but the element
     * content from B (renamed P) should be moved to the new path.
     *
     * Expected end result:
     *   model/business/folderZ/folderY/ with element P (renamed by B)
     *   model/business/folderX/ removed (consolidated)
     */
    @Test
    public void consolidateMoveGroups_FolderMoveRenameAndElementRename() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "moveRenameRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            File modelDir = new File(repoFolder, "model");
            File bizDir = new File(modelDir, "business");
            File folderX = new File(bizDir, "folderX");

            // === Initial state (common ancestor) ===
            writeGraficoModel(modelDir);

            mkdirAndWrite(bizDir, "folder.xml",
                    "<archimate:Folder " + NS + " name=\"Business\" id=\"id-biz\" type=\"business\"/>\n");
            mkdirAndWrite(folderX, "folder.xml",
                    "<archimate:Folder " + NS + " name=\"FolderX\" id=\"id-folderX\"/>\n");
            Files.writeString(new File(folderX, "BusinessActor_id-q.xml").toPath(),
                    "<archimate:BusinessActor " + NS + " name=\"Q\" id=\"id-q\"/>\n");

            // Empty standard folders required by importer
            writeStandardFolders(modelDir);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with element Q").call();

                // === Branch A: move folderX into folderZ and rename to folderY ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                // Create new structure
                File folderZ = new File(bizDir, "folderZ");
                File folderY = new File(folderZ, "folderY");
                mkdirAndWrite(folderZ, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderZ\" id=\"id-folderZ\"/>\n");
                mkdirAndWrite(folderY, "folder.xml",
                        "<archimate:Folder " + NS + " name=\"FolderY\" id=\"id-folderX\"/>\n");
                Files.writeString(new File(folderY, "BusinessActor_id-q.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"Q\" id=\"id-q\"/>\n");

                // Remove old path
                new File(folderX, "BusinessActor_id-q.xml").delete();
                new File(folderX, "folder.xml").delete();
                folderX.delete();

                git.add().addFilepattern(".").call();
                // Also stage deletions
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX to folderZ/folderY").call();

                // === Branch B: rename element Q to P at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                Files.writeString(new File(folderX, "BusinessActor_id-q.xml").toPath(),
                        "<archimate:BusinessActor " + NS + " name=\"P\" id=\"id-q\"/>\n");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to P").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Should have conflicts (folder.xml and/or element at old path)
                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Merge should produce conflicts, got: " + mergeResult.getMergeStatus()
                        + " conflicts: " + mergeResult.getConflicts());

                // === Load models for the handler ===
                // "Ours" = branchB's HEAD (current checkout) — import from disk
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel, "Should import our model");

                // "Theirs" = branchA — import from commit tree
                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel, "Should import their model");

                // === Set up MergeConflictHandler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Should detect move group (id-folderX at old path vs new path on disk)
                assertTrue(handler.hasMoveGroups(),
                        "Should detect folder move. Conflicts: " + mergeResult.getConflicts().keySet());
                assertEquals(1, handler.getMoveGroups().size());

                MergeConflictHandler.MoveGroup moveGroup = handler.getMoveGroups().get(0);
                assertNotNull(moveGroup.theirsFolderPath, "Should have theirs path");
                assertNotNull(moveGroup.oursFolderPath, "Should have ours path");

                // === Set user choice: keep new location (branch A's structure) ===
                // Location choice is on the group itself
                moveGroup.locationChoice = MergeObjectInfo.THEIRS;
                
                // Element content choice: user wants branch B's rename (P) = OURS
                // (OURS = HEAD = branchB which renamed Q to P)
                for(MergeObjectInfo info : moveGroup.relatedInfos) {
                    if(!info.isFolderXml()) {
                        info.setUserChoice(MergeObjectInfo.OURS);
                    }
                }

                // === Execute merge + consolidation ===
                handler.merge();

                // === Verify end result ===
                File resultFolderY = new File(bizDir, "folderZ/folderY");

                // 1. The new folder structure should exist with the moved folder's ID
                File newFolderXml = new File(resultFolderY, "folder.xml");
                assertTrue(newFolderXml.exists(),
                        "folderZ/folderY/folder.xml should exist");
                String newFolderContent = Files.readString(newFolderXml.toPath());
                assertTrue(newFolderContent.contains("id-folderX"),
                        "Should keep the original folder ID");

                // 2. Element P should exist at the new location (moved from unchosen + renamed by B)
                File elementFile = new File(resultFolderY, "BusinessActor_id-q.xml");
                assertTrue(elementFile.exists(),
                        "Element should exist at new location (folderZ/folderY/)");
                String elementContent = Files.readString(elementFile.toPath());
                assertTrue(elementContent.contains("name=\"P\""),
                        "Element should have the renamed name 'P' from branch B, got: " + elementContent);

                // 3. Old folderX should be cleaned up
                assertFalse(new File(folderX, "folder.xml").exists(),
                        "Old folderX/folder.xml should be removed");
                assertFalse(new File(folderX, "BusinessActor_id-q.xml").exists(),
                        "Old element should be removed from folderX");

                // 4. No duplicate folder IDs on disk
                if(folderX.exists()) {
                    File[] remainingFiles = folderX.listFiles();
                    assertTrue(remainingFiles == null || remainingFiles.length == 0,
                            "Old folderX should be empty or deleted");
                }
            }
        }
    }

    // ========================================================================
    // Helper methods
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
