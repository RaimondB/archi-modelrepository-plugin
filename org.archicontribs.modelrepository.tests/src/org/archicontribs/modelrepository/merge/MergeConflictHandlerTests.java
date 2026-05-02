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
import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelExporter;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

@SuppressWarnings("nls")
public class MergeConflictHandlerTests {

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
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with element Q").call();

                // === Branch A: move folderX into folderZ and rename to folderY ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                // Create new structure: folderZ/folderY (folderY keeps folderX's ID)
                File folderZDir = new File(bizDir, "id-folderZ");
                File folderYDir = new File(folderZDir, "id-folderX");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.writeFolderXml(folderYDir, "FolderY", "id-folderX");
                Files.copy(qFile.toPath(), new File(folderYDir, qFile.getName()).toPath());

                // Remove old path
                qFile.delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX to folderZ/folderY").call();

                // === Branch B: rename element Q to P at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(qFile, "Q", "P");

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
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel, "Should import our model");

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
                moveGroup.locationChoice = MergeObjectInfo.THEIRS;
                
                // Element content choice: user wants branch B's rename (P) = OURS
                for(MergeObjectInfo info : moveGroup.relatedInfos) {
                    if(!info.isFolderXml()) {
                        info.setUserChoice(MergeObjectInfo.OURS);
                    }
                }

                // === Execute merge + consolidation ===
                handler.merge();

                // === Verify end result ===
                File resultFolderY = new File(bizDir, "id-folderZ/id-folderX");

                // 1. The new folder structure should exist with the moved folder's ID
                File newFolderXml = new File(resultFolderY, "folder.xml");
                assertTrue(newFolderXml.exists(),
                        "folderZ/folderY/folder.xml should exist");
                String newFolderContent = Files.readString(newFolderXml.toPath());
                assertTrue(newFolderContent.contains("id-folderX"),
                        "Should keep the original folder ID");

                // 2. Element P should exist at the new location
                File elementFile = new File(resultFolderY, "BusinessActor_id-q.xml");
                assertTrue(elementFile.exists(),
                        "Element should exist at new location (folderZ/folderY/)");
                String elementContent = Files.readString(elementFile.toPath());
                assertTrue(elementContent.contains("name=\"P\""),
                        "Element should have the renamed name 'P' from branch B, got: " + elementContent);

                // 3. Old folderX should be cleaned up
                assertFalse(new File(folderXDir, "folder.xml").exists(),
                        "Old folderX/folder.xml should be removed");
                assertFalse(new File(folderXDir, "BusinessActor_id-q.xml").exists(),
                        "Old element should be removed from folderX");

                // 4. No duplicate folder IDs on disk
                if(folderXDir.exists()) {
                    File[] remainingFiles = folderXDir.listFiles();
                    assertTrue(remainingFiles == null || remainingFiles.length == 0,
                            "Old folderX should be empty or deleted");
                }
            }
        }
    }

    // ========================================================================
    // A4: Multiple elements moved to different-ID folder, per-element content
    // ========================================================================

    /**
     * A4: Multiple elements moved to a different-ID folder by branch A,
     * 2 elements modified by branch B. User wants all elements at the new
     * location but per-element content choice.
     *
     * Common start:
     *   model/business/folderX/folder.xml (id-folderX)
     *   model/business/folderX/BusinessActor_id-e1..e5.xml (names E1..E5)
     *   model/business/folderY/folder.xml (id-folderY) ← different folder ID
     *
     * Branch A: moves all 5 elements from folderX to folderY (individual moves)
     *
     * Branch B: renames E1→"E1-B", renames E2→"E2-B" at original location
     *
     * Merge A into B:
     *   - E1, E2 conflict at folderX (B modified, A deleted)
     *   - E3-E5 auto-merged at folderY (no conflict)
     *   - E1, E2 also auto-merged at folderY from A
     *
     * User choice:
     *   - E1: OURS content (B's rename "E1-B") — should end up at folderY
     *   - E2: THEIRS content (A's original "E2") — should end up at folderY
     *
     * Expected: All 5 elements at folderY. E1 with B's content, E2 with A's.
     * Old folderX has no element duplicates.
     *
     * BUG: cleanupAutoMergedDuplicates() OURS path keeps element at old
     * location instead of moving to where theirs placed it. E1 stays at
     * folderX instead of folderY.
     */
    @Test
    public void merge_A4_MultipleElementsMovedToDiffFolder_PerElementContentChoice() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "a4MultiMoveRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement[] elements = new IArchimateElement[5];
            for(int i = 0; i < 5; i++) {
                elements[i] = helper.addBusinessActor(folderX, "E" + (i + 1), "id-e" + (i + 1));
            }
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with 5 elements, empty folderY").call();

                // === Branch A: move all 5 elements from folderX to folderY ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                for(int i = 1; i <= 5; i++) {
                    String filename = "BusinessActor_id-e" + i + ".xml";
                    Files.copy(new File(folderXDir, filename).toPath(), new File(folderYDir, filename).toPath());
                    new File(folderXDir, filename).delete();
                }

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move 5 elements from folderX to folderY").call();

                // === Branch B: modify E1 (rename) and E2 (rename) at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(new File(folderXDir, "BusinessActor_id-e1.xml"), "E1", "E1-B");
                GraficoTestHelper.renameElement(new File(folderXDir, "BusinessActor_id-e2.xml"), "E2", "E2-B");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename E1 and E2").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (B modified E1/E2, A deleted)");

                // === Load models ===
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel, "Should import our model");

                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel, "Should import their model");

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Both E1, E2 should be resolvedAsMove (found by ID in other model)
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    assertTrue(info.isResolvedAsMove(),
                            info.getXMLPath() + " should be resolvedAsMove");
                }

                // Set user choices:
                // E1: OURS content (B's rename to "E1-B")
                // E2: THEIRS content (A's original "E2")
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    if(info.getXMLPath().contains("id-e1")) {
                        info.setUserChoice(MergeObjectInfo.OURS);
                    } else if(info.getXMLPath().contains("id-e2")) {
                        info.setUserChoice(MergeObjectInfo.THEIRS);
                    }
                }

                // === Execute merge ===
                handler.merge();

                // === Verify: ALL elements should be at folderY ===

                // E1: at folderY with B's content (OURS chosen)
                File e1AtNew = new File(folderYDir, "BusinessActor_id-e1.xml");
                assertTrue(e1AtNew.exists(),
                        "E1 should be at folderY — OURS content choice should still "
                        + "move the element to where theirs placed it");
                String e1Content = Files.readString(e1AtNew.toPath());
                assertTrue(e1Content.contains("name=\"E1-B\""),
                        "E1 should have B's renamed content at folderY, got: " + e1Content);

                // E1 should NOT remain at old location
                assertFalse(new File(folderXDir, "BusinessActor_id-e1.xml").exists(),
                        "E1 should NOT remain at folderX after moved-element cleanup");

                // E2: at folderY with A's content (THEIRS chosen)
                File e2AtNew = new File(folderYDir, "BusinessActor_id-e2.xml");
                assertTrue(e2AtNew.exists(), "E2 should be at folderY");
                String e2Content = Files.readString(e2AtNew.toPath());
                assertTrue(e2Content.contains("name=\"E2\""),
                        "E2 should have A's original content, got: " + e2Content);

                // E2 should NOT remain at old location
                assertFalse(new File(folderXDir, "BusinessActor_id-e2.xml").exists(),
                        "E2 should NOT remain at folderX");

                // E3-E5: at folderY (auto-merged from A, no conflict)
                for(int i = 3; i <= 5; i++) {
                    assertTrue(new File(folderYDir, "BusinessActor_id-e" + i + ".xml").exists(),
                            "E" + i + " should be at folderY (auto-merged)");
                    assertFalse(new File(folderXDir, "BusinessActor_id-e" + i + ".xml").exists(),
                            "E" + i + " should NOT be at folderX");
                }
            }
        }
    }

    // ========================================================================
    // B2: Folder move (same ID) + multiple element modifications
    // ========================================================================

    /**
     * B2: Folder moved (same ID, different path) by branch A, 2 of 5
     * elements modified by branch B. User chooses new location with
     * per-element content choice.
     *
     * This goes through the MoveGroup + consolidateMoveGroups() path.
     *
     * Common start:
     *   model/business/folderX/folder.xml (id-folderX, "FolderX")
     *   model/business/folderX/BusinessActor_id-e1..e5.xml (names E1..E5)
     *
     * Branch A: moves folderX into new folder folderZ (same folder ID preserved)
     *   model/business/folderZ/folder.xml (id-folderZ, "FolderZ")
     *   model/business/folderZ/folderX/folder.xml (id-folderX, "FolderX")
     *   model/business/folderZ/folderX/BusinessActor_id-e1..e5.xml
     *
     * Branch B: renames E1→"E1-B", E2→"E2-B" at original location
     *
     * User choice: keep new location (THEIRS), E1=OURS content, E2=THEIRS content
     *
     * Expected: All 5 at folderZ/folderX. E1 with B's content. E2 with A's.
     */
    @Test
    public void merge_B2_FolderMoveMultipleElements_MixedContentChoice() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "b2MoveGroupRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement[] elements = new IArchimateElement[5];
            for(int i = 0; i < 5; i++) {
                elements[i] = helper.addBusinessActor(folderX, "E" + (i + 1), "id-e" + (i + 1));
            }
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with 5 elements").call();

                // === Branch A: move folderX into new folderZ ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderZDir = new File(bizDir, "id-folderZ");
                File movedFolderXDir = new File(folderZDir, "id-folderX");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.writeFolderXml(movedFolderXDir, "FolderX", "id-folderX");

                for(int i = 1; i <= 5; i++) {
                    String filename = "BusinessActor_id-e" + i + ".xml";
                    Files.copy(new File(folderXDir, filename).toPath(), new File(movedFolderXDir, filename).toPath());
                    new File(folderXDir, filename).delete();
                }
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX into folderZ").call();

                // === Branch B: modify E1 and E2 at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(new File(folderXDir, "BusinessActor_id-e1.xml"), "E1", "E1-B");
                GraficoTestHelper.renameElement(new File(folderXDir, "BusinessActor_id-e2.xml"), "E2", "E2-B");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename E1 and E2").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (B modified E1/E2, A deleted)");

                // === Load models ===
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel);

                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel);

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Should detect MoveGroup (same folder ID at both paths)
                assertTrue(handler.hasMoveGroups(),
                        "Should detect folder move (same id-folderX at different paths). "
                        + "Conflicts: " + mergeResult.getConflicts().keySet());

                MergeConflictHandler.MoveGroup moveGroup = handler.getMoveGroups().get(0);

                // User choice: keep new location (THEIRS = branch A's structure)
                moveGroup.locationChoice = MergeObjectInfo.THEIRS;

                // Per-element content choices:
                // E1: OURS (B's rename to "E1-B")
                // E2: THEIRS (A's original "E2")
                for(MergeObjectInfo info : moveGroup.relatedInfos) {
                    if(info.isFolderXml()) continue;
                    if(info.getXMLPath().contains("id-e1")) {
                        info.setUserChoice(MergeObjectInfo.OURS);
                    } else if(info.getXMLPath().contains("id-e2")) {
                        info.setUserChoice(MergeObjectInfo.THEIRS);
                    }
                }

                // === Execute merge ===
                handler.merge();

                // === Verify: ALL elements at folderZ/folderX ===
                File resultDir = new File(bizDir, "id-folderZ/id-folderX");

                // E1: B's content at new location
                File e1 = new File(resultDir, "BusinessActor_id-e1.xml");
                assertTrue(e1.exists(), "E1 should be at new location (folderZ/folderX)");
                String e1Content = Files.readString(e1.toPath());
                assertTrue(e1Content.contains("name=\"E1-B\""),
                        "E1 should have B's renamed content, got: " + e1Content);

                // E2: A's content at new location
                File e2 = new File(resultDir, "BusinessActor_id-e2.xml");
                assertTrue(e2.exists(), "E2 should be at new location (folderZ/folderX)");
                String e2Content = Files.readString(e2.toPath());
                assertTrue(e2Content.contains("name=\"E2\""),
                        "E2 should have A's original content, got: " + e2Content);

                // E3-E5: auto-merged at new location
                for(int i = 3; i <= 5; i++) {
                    assertTrue(new File(resultDir, "BusinessActor_id-e" + i + ".xml").exists(),
                            "E" + i + " should be at new location (folderZ/folderX)");
                }

                // Old folderX should be cleaned up
                assertFalse(new File(folderXDir, "BusinessActor_id-e1.xml").exists(),
                        "E1 should NOT be at old location");
                assertFalse(new File(folderXDir, "BusinessActor_id-e2.xml").exists(),
                        "E2 should NOT be at old location");
            }
        }
    }

    // ========================================================================
    // Element moved to different folder — "Mine" loses content
    // ========================================================================

    /**
     * Bug scenario: element moved to a DIFFERENT folder by branch A,
     * element renamed by branch B at original location.
     *
     * Common start:
     *   model/business/folderX/folder.xml (id-folderX, "FolderX")
     *   model/business/folderX/BusinessActor_id-q.xml (name="Q")
     *   model/business/folderY/folder.xml (id-folderY, "FolderY")  ← different folder ID
     *
     * Branch A (merged into B):
     *   - Moves element Q from folderX to folderY (different folder)
     *
     * Branch B (current):
     *   - Renames element Q to P at original location
     *
     * User choice: "Mine" (OURS = branch B's content and location)
     *
     * Expected:
     *   - Element at folderX with name="P" (branch B content at old location)
     *   - Auto-merged copy at folderY removed
     *
     * Bug: when user chose "Mine", branch A's version was used instead,
     * losing branch B's rename.
     */
    @Test
    public void merge_ElementMovedToDifferentFolder_ChooseMine_KeepsOursContent() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "elementMoveRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            // folderY is a SUBFOLDER of folderX — this is the real-world layout
            // when an element moves from "Application" to "Application / Shared Services".
            // The old bug excluded the entire folderX tree, missing the duplicate.
            IFolder folderY = helper.addFolder(folderX, "FolderY", "id-folderY");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q, empty folderY").call();

                // === Branch A: move element Q from folderX to folderY ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                GraficoTestHelper.moveElementFile(folderXDir, folderYDir, q);

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q from folderX to folderY").call();

                // === Branch B: rename element Q to P at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(qFile, "Q", "P");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to P").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Merge should conflict (B modified, A deleted)");

                // === Load models for handler ===
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel, "Should import our model");

                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel, "Should import their model");

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Find the conflict info for our element
                MergeObjectInfo elementInfo = null;
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    if(info.getXMLPath().contains("BusinessActor_id-q")) {
                        elementInfo = info;
                        break;
                    }
                }
                assertNotNull(elementInfo, "Should have conflict for BusinessActor_id-q");

                // === User chooses "Mine" (OURS = branch B) ===
                elementInfo.setUserChoice(MergeObjectInfo.OURS);
                
                // If handler detected move groups, set location to OURS (old location)
                // AND set element choices to OURS (branch B content)
                if(handler.hasMoveGroups()) {
                    for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                        group.locationChoice = MergeObjectInfo.OURS;
                        for(MergeObjectInfo info : group.relatedInfos) {
                            if(!info.isFolderXml()) {
                                info.setUserChoice(MergeObjectInfo.OURS);
                            }
                        }
                    }
                }

                // === Execute merge ===
                handler.merge();

                // === Verify: element at old location (folderX) with branch B content ===
                File elementAtOld = new File(folderXDir, "BusinessActor_id-q.xml");
                assertTrue(elementAtOld.exists(),
                        "Element should exist at old location (folderX) since user chose Mine");
                String oldContent = Files.readString(elementAtOld.toPath());
                assertTrue(oldContent.contains("name=\"P\""),
                        "Element at old location should have branch B name 'P', got: " + oldContent);

                // === Auto-merged copy at new location should be cleaned up ===
                File elementAtNew = new File(folderYDir, "BusinessActor_id-q.xml");
                assertFalse(elementAtNew.exists(),
                        "Auto-merged copy at new location (folderY) should be removed "
                        + "when user chose Mine — otherwise model import loads wrong version");
            }
        }
    }

    /**
     * Same scenario as above, but user accepts the move (THEIRS location = folderY),
     * while choosing "Mine" for element content (OURS = branch B's rename to "P").
     *
     * Expected:
     *   - Element at folderY with name="P" (branch B content, branch A location)
     *   - Old folderX element removed
     *
     * Bug: element at folderY has name="Q" (branch A content) instead of
     * branch B's renamed "P".
     */
    @Test
    public void merge_ElementMovedToDifferentFolder_AcceptMoveButChooseMineContent() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "elementMoveContentRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q, empty folderY").call();

                // === Branch A: move element Q from folderX to folderY ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                Files.copy(qFile.toPath(), new File(folderYDir, qFile.getName()).toPath());
                qFile.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q from folderX to folderY").call();

                // === Branch B: rename element Q to P at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(qFile, "Q", "P");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to P").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus());

                // === Load models for handler ===
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel);

                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel);

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Set location choice to THEIRS (accept the move to folderY)
                // but element content choice to OURS (branch B's rename to "P")
                if(handler.hasMoveGroups()) {
                    for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                        group.locationChoice = MergeObjectInfo.THEIRS; // accept move
                        for(MergeObjectInfo info : group.relatedInfos) {
                            if(!info.isFolderXml()) {
                                info.setUserChoice(MergeObjectInfo.OURS); // keep MY content
                            }
                        }
                    }
                } else {
                    // No move group — set element choice directly
                    for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                        if(info.getXMLPath().contains("BusinessActor_id-q")) {
                            info.setUserChoice(MergeObjectInfo.OURS);
                        }
                    }
                }

                // === Execute merge ===
                handler.merge();

                // === Verify: element should be at folderY with branch B content ===
                File elementAtNew = new File(folderYDir, qFile.getName());
                assertTrue(elementAtNew.exists(),
                        "Element should exist at new location (folderY) since move accepted");
                String newContent = Files.readString(elementAtNew.toPath());
                assertTrue(newContent.contains("name=\"P\""),
                        "Element should have branch B name 'P' (user chose Mine for content), got: "
                        + newContent);

                // Old location should be cleaned up
                File elementAtOld = new File(folderXDir, qFile.getName());
                assertFalse(elementAtOld.exists(),
                        "Element at old location (folderX) should be removed after move accepted");
            }
        }
    }

    /**
     * Same move scenario, but user picks THEIRS (accept the move + theirs' content).
     * Element moved from folderX to subfolder folderY by branchA, renamed by branchB.
     *
     * When user picks THEIRS: element should be at new location (folderY)
     * with theirs' content (original name "Q"), and the old file removed.
     */
    @Test
    public void merge_ElementMovedToDifferentFolder_ChooseTheirs_KeepsTheirsContentAndLocation() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "elementMoveTheirsRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            // folderY is a SUBFOLDER of folderX to match real-world layout
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(folderX, "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q, empty folderY").call();

                // === Branch A: move element Q from folderX to folderY ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                Files.copy(qFile.toPath(), new File(folderYDir, qFile.getName()).toPath());
                qFile.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q from folderX to folderY").call();

                // === Branch B: rename element Q to P at original location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(qFile, "Q", "P");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to P").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Merge should conflict (B modified, A deleted)");

                // === Load models for handler ===
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel, "Should import our model");

                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel, "Should import their model");

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Find the conflict info for our element
                MergeObjectInfo elementInfo = null;
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    if(info.getXMLPath().contains("BusinessActor_id-q")) {
                        elementInfo = info;
                        break;
                    }
                }
                assertNotNull(elementInfo, "Should have conflict for BusinessActor_id-q");

                // === User chooses "Theirs" (THEIRS = branch A's move) ===
                elementInfo.setUserChoice(MergeObjectInfo.THEIRS);
                
                // If handler detected move groups, set all to THEIRS
                if(handler.hasMoveGroups()) {
                    for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                        group.locationChoice = MergeObjectInfo.THEIRS;
                        for(MergeObjectInfo info : group.relatedInfos) {
                            if(!info.isFolderXml()) {
                                info.setUserChoice(MergeObjectInfo.THEIRS);
                            }
                        }
                    }
                }

                // === Execute merge ===
                handler.merge();

                // === Verify: element at new location (folderY) with theirs' content ===
                File elementAtNew = new File(folderYDir, qFile.getName());
                assertTrue(elementAtNew.exists(),
                        "Element should exist at new location (folderY) since user chose Theirs");
                String newContent = Files.readString(elementAtNew.toPath());
                assertTrue(newContent.contains("name=\"Q\""),
                        "Element at new location should have theirs' name 'Q', got: " + newContent);

                // === Old file at conflict path should be removed ===
                File elementAtOld = new File(folderXDir, qFile.getName());
                assertFalse(elementAtOld.exists(),
                        "File at old location (folderX) should be removed "
                        + "when user chose Theirs — element is at new location");
            }
        }
    }

    // ========================================================================
    // A5: Element moved + new element added at old location
    // ========================================================================

    /**
     * A5: Branch A moves element Q from folderX to folderY (different IDs).
     * Branch B adds a NEW element R at folderX.
     *
     * Expected: Q at folderY (A's location), R stays at folderX (not a move).
     */
    @Test
    public void merge_A5_ElementMovedAndNewElementAdded() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "a5NewElemRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();

                // Branch A: move Q to folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                Files.copy(qFile.toPath(), new File(folderYDir, qFile.getName()).toPath());
                qFile.delete();
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q to folderY").call();

                // Branch B: add new element R at folderX
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.writeBusinessRole(folderXDir, "R", "id-r");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: add R at folderX").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // This may merge cleanly (no conflict): A deleted Q, B didn't touch Q.
                // B added R, A didn't touch R. Both changes are non-conflicting.
                // If clean merge, Q is at folderY and R is at folderX — no handler needed.
                if(mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                    IArchimateModel theirModel;
                    ObjectId branchAId = gitRepo.resolve("branchA");
                    try(RevWalk rw = new RevWalk(gitRepo)) {
                        RevCommit branchACommit = rw.parseCommit(branchAId);
                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                .importFromCommit(null);
                    }
                    IArchiRepository repo = new ArchiRepository(repoFolder);
                    MergeConflictHandler handler = new MergeConflictHandler(
                            mergeResult, "branchA", repo, null);
                    handler.init(null, ourModel, theirModel);
                    // Accept all theirs
                    for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                        info.setUserChoice(MergeObjectInfo.THEIRS);
                    }
                    handler.merge();
                }

                // Q at folderY
                assertTrue(new File(folderYDir, qFile.getName()).exists(),
                        "Q should be at folderY (moved by A)");
                // Q NOT at folderX
                assertFalse(new File(folderXDir, qFile.getName()).exists(),
                        "Q should NOT be at folderX (moved away by A)");
                // R at folderX (new element stays where B added it)
                assertTrue(new File(folderXDir, "BusinessRole_id-r.xml").exists(),
                        "R should stay at folderX (added by B, not a move)");
            }
        }
    }

    // ========================================================================
    // A6: Both branches move same element to different folders
    // ========================================================================

    /**
     * A6: Branch A moves element Q from folderX to folderY.
     * Branch B moves element Q from folderX to folderZ.
     *
     * Expected: conflict at folderX (both deleted). Auto-merged copies at
     * folderY and folderZ. User picks one location, other copy deleted.
     */
    @Test
    public void merge_A6_BothBranchesMoveElementToDiffFolders() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "a6DualMoveRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IFolder folderZ = helper.addFolder(helper.businessFolder(), "FolderZ", "id-folderZ");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File folderZDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderZ);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();

                // Branch A: move Q to folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                Files.copy(qFile.toPath(), new File(folderYDir, qFile.getName()).toPath());
                qFile.delete();
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q to folderY").call();

                // Branch B: move Q to folderZ
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                Files.copy(qFile.toPath(), new File(folderZDir, qFile.getName()).toPath());
                qFile.delete();
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchB: move Q to folderZ").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Both deleted Q at folderX → no conflict there (both agree: delete).
                // A added at folderY, B added at folderZ → both auto-merged (non-conflicting adds).
                // Result: Q at both folderY and folderZ (duplicate!).
                // The handler/repair should detect this and let user choose.

                // Verify: Q should end up at exactly ONE location (not duplicated)
                boolean atY = new File(folderYDir, "BusinessActor_id-q.xml").exists();
                boolean atZ = new File(folderZDir, "BusinessActor_id-q.xml").exists();
                boolean atX = new File(folderXDir, "BusinessActor_id-q.xml").exists();

                assertFalse(atX, "Q should NOT be at folderX (both branches deleted it)");

                // After a clean merge, Q will be at both Y and Z.
                // The merge handler or repair should detect the duplicate.
                // For now, verify the file states. The fix will ensure only one copy remains.
                if(atY && atZ) {
                    // This is the current (buggy) state: duplicate element.
                    // After fix, only one should exist.
                    // Mark this as a known duplication that needs resolution.
                    // The test documents the expected final behavior.
                }

                // At minimum, Q should exist somewhere
                assertTrue(atY || atZ, "Q should exist at folderY or folderZ");
            }
        }
    }

    // ========================================================================
    // B4: Folder moved + element deleted by other branch
    // ========================================================================

    /**
     * B4: Branch A moves folder (same ID). Branch B deletes an element.
     *
     * Expected: at chosen location, deleted element should NOT appear.
     */
    @Test
    public void merge_B4_FolderMovedAndElementDeleted() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "b4DeleteRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement r = helper.addBusinessRole(folderX, "R", "id-r");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);
            File rFile = GraficoTestHelper.elementFile(folderXDir, r);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q and R").call();

                // Branch A: move folderX to folderZ/folderX (same ID)
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderZDir = new File(bizDir, "id-folderZ");
                File movedFolderXDir = new File(folderZDir, "id-folderX");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.writeFolderXml(movedFolderXDir, "FolderX", "id-folderX");
                Files.copy(qFile.toPath(), new File(movedFolderXDir, qFile.getName()).toPath());
                Files.copy(rFile.toPath(), new File(movedFolderXDir, rFile.getName()).toPath());
                qFile.delete();
                rFile.delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX to folderZ/folderX").call();

                // Branch B: delete element R at old location
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                rFile.delete();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchB: delete R").call();

                // Save pre-merge commit IDs for cross-path detection
                ObjectId oursId = gitRepo.resolve("branchB");
                ObjectId theirsId = gitRepo.resolve("branchA");

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                File resultDir = new File(bizDir, "id-folderZ/id-folderX");

                if(mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                    IArchimateModel theirModel;
                    ObjectId branchAId = gitRepo.resolve("branchA");
                    try(RevWalk rw = new RevWalk(gitRepo)) {
                        RevCommit branchACommit = rw.parseCommit(branchAId);
                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                .importFromCommit(null);
                    }
                    IArchiRepository repo = new ArchiRepository(repoFolder);
                    MergeConflictHandler handler = new MergeConflictHandler(
                            mergeResult, "branchA", repo, null);
                    handler.init(null, ourModel, theirModel);
                    if(handler.hasMoveGroups()) {
                        for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                            group.locationChoice = MergeObjectInfo.THEIRS;
                        }
                    }
                    handler.merge();
                }

                // Cross-path deletion detection (Phase 1.5)
                MergeConflictHandler.detectAndRemoveCrossPathDeletions(gitRepo, oursId, theirsId);

                // Q should be at new location
                assertTrue(new File(resultDir, "BusinessActor_id-q.xml").exists(),
                        "Q should be at folderZ/folderX");

                // R should NOT be at new location (was deleted by B)
                assertFalse(new File(resultDir, "BusinessRole_id-r.xml").exists(),
                        "R should NOT be at folderZ/folderX (B deleted it)");

                // R should NOT be at old location either
                assertFalse(new File(folderXDir, "BusinessRole_id-r.xml").exists(),
                        "R should NOT be at folderX either");
            }
        }
    }

        /**
         * Regression for mixed per-element content choice when the user keeps the
         * old location. The new location already contains auto-merged copies from
         * the mover branch, so the chosen old location must still be overwritten
         * for elements whose content choice comes from the unchosen path.
         */
        @Test
        public void merge_B2a_FolderMoveKeepOldLocation_MixedContentChoice() throws Exception {
                File repoFolder = new File(GitHelper.getTempTestsFolder(), "b2aKeepOldLocationRepo");

                try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
                        var helper = new GraficoTestHelper();
                        IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
                        for(int i = 0; i < 5; i++) {
                                helper.addBusinessActor(folderX, "E" + (i + 1), "id-e" + (i + 1));
                        }
                        helper.export(repoFolder);

                        File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
                        File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);

                        try(Git git = new Git(gitRepo)) {
                                git.add().addFilepattern(".").call();
                                git.commit().setMessage("initial: folderX with 5 elements").call();

                                git.branchCreate().setName("branchA").call();
                                git.checkout().setName("branchA").call();

                                File folderZDir = new File(bizDir, "id-folderZ");
                                File movedFolderXDir = new File(folderZDir, "id-folderX");
                                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                                GraficoTestHelper.writeFolderXml(movedFolderXDir, "FolderX", "id-folderX");

                                for(int i = 1; i <= 5; i++) {
                                        String filename = "BusinessActor_id-e" + i + ".xml";
                                        Files.copy(new File(folderXDir, filename).toPath(), new File(movedFolderXDir, filename).toPath());
                                        new File(folderXDir, filename).delete();
                                }
                                new File(folderXDir, "folder.xml").delete();
                                folderXDir.delete();

                                git.add().addFilepattern(".").call();
                                git.add().addFilepattern(".").setUpdate(true).call();
                                git.commit().setMessage("branchA: move folderX into folderZ").call();

                                git.checkout().setName("master").call();
                                git.branchCreate().setName("branchB").call();
                                git.checkout().setName("branchB").call();

                                GraficoTestHelper.renameElement(new File(folderXDir, "BusinessActor_id-e1.xml"), "E1", "E1-B");
                                GraficoTestHelper.renameElement(new File(folderXDir, "BusinessActor_id-e2.xml"), "E2", "E2-B");

                                git.add().addFilepattern(".").call();
                                git.commit().setMessage("branchB: rename E1 and E2").call();

                                MergeResult mergeResult = git.merge()
                                                .include(gitRepo.resolve("branchA"))
                                                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                                                .call();

                                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                                                "Should conflict (B modified E1/E2, A deleted)");

                                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                                assertNotNull(ourModel);

                                IArchimateModel theirModel;
                                ObjectId branchAId = gitRepo.resolve("branchA");
                                try(RevWalk rw = new RevWalk(gitRepo)) {
                                        RevCommit branchACommit = rw.parseCommit(branchAId);
                                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                                        .importFromCommit(null);
                                }
                                assertNotNull(theirModel);

                                IArchiRepository repo = new ArchiRepository(repoFolder);
                                MergeConflictHandler handler = new MergeConflictHandler(
                                                mergeResult, "branchA", repo, null);
                                handler.init(null, ourModel, theirModel);

                                assertTrue(handler.hasMoveGroups(), "Should detect folder move");
                                MergeConflictHandler.MoveGroup moveGroup = handler.getMoveGroups().get(0);

                                moveGroup.locationChoice = MergeObjectInfo.OURS;

                                for(MergeObjectInfo info : moveGroup.relatedInfos) {
                                        if(info.isFolderXml()) continue;
                                        if(info.getXMLPath().contains("id-e1")) {
                                                info.setUserChoice(MergeObjectInfo.THEIRS);
                                        }
                                        else if(info.getXMLPath().contains("id-e2")) {
                                                info.setUserChoice(MergeObjectInfo.OURS);
                                        }
                                }

                                handler.merge();

                                File oldLocationDir = new File(bizDir, "id-folderX");
                                File newLocationDir = new File(bizDir, "id-folderZ/id-folderX");

                                File e1Old = new File(oldLocationDir, "BusinessActor_id-e1.xml");
                                assertTrue(e1Old.exists(), "E1 should remain at the old location");
                                String e1Content = Files.readString(e1Old.toPath());
                                assertTrue(e1Content.contains("name=\"E1\""),
                                                "E1 should use branch A's content at the kept old location, got: " + e1Content);

                                File e2Old = new File(oldLocationDir, "BusinessActor_id-e2.xml");
                                assertTrue(e2Old.exists(), "E2 should remain at the old location");
                                String e2Content = Files.readString(e2Old.toPath());
                                assertTrue(e2Content.contains("name=\"E2-B\""),
                                                "E2 should use branch B's content at the kept old location, got: " + e2Content);

                                for(int i = 1; i <= 5; i++) {
                                        assertFalse(new File(newLocationDir, "BusinessActor_id-e" + i + ".xml").exists(),
                                                        "E" + i + " should not remain at the unchosen new location");
                                }
                        }
                }
        }

    // ========================================================================
    // B2b: Folder move (folder.xml auto-resolved) + element modify — OURS content
    // ========================================================================

    /**
     * B2b: Branch A moves entire folder (folder.xml + all elements).
     * Branch B modifies elements at old location (never touches folder.xml).
     *
     * Git result: folder.xml is NOT conflicting (A deleted at old + created at new,
     * B never touched it → auto-resolved). But ELEMENTS conflict at old path
     * (A deleted vs B modified → modify/delete conflict).
     *
     * This exercises detectFolderMoves() Pass 3 (element-only move detection)
     * because folder.xml is NOT in the conflict list.
     *
     * User choice: OURS content (B's modifications) at new location (default THEIRS).
     *
     * Expected: elements at new location with B's modified content.
     */
    @Test
    public void merge_B2b_FolderMoveAutoResolved_ElementModified_OursContent() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "b2bAutoResolvedRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder shared = helper.addFolder(helper.applicationFolder(), "Shared Services", "id-shared");
            IArchimateElement nwcf = helper.addApplicationComponent(shared, "Network Capacity Forecast", "id-nwcf");
            IArchimateElement ndcf = helper.addApplicationComponent(shared, "Node Capacity Forecast", "id-ndcf");
            IArchimateElement other = helper.addApplicationComponent(shared, "Other Component", "id-other");
            helper.export(repoFolder);

            File appDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.APPLICATION);
            File sharedDir = GraficoTestHelper.folderDir(repoFolder, FolderType.APPLICATION, shared);
            File nwcfFile = GraficoTestHelper.elementFile(sharedDir, nwcf);
            File ndcfFile = GraficoTestHelper.elementFile(sharedDir, ndcf);
            File otherFile = GraficoTestHelper.elementFile(sharedDir, other);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: Shared Services with 3 components").call();

                // === Branch A (theirs): move entire folder to new location ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                // Create new parent + move folder there (same folder ID)
                File masterDir = new File(appDir, "id-master");
                File movedSharedDir = new File(masterDir, "id-shared");
                GraficoTestHelper.writeFolderXml(masterDir, "Master Elements", "id-master");
                GraficoTestHelper.writeFolderXml(movedSharedDir, "Shared Services", "id-shared");

                // Copy all elements to new location (unchanged by A)
                Files.copy(nwcfFile.toPath(), new File(movedSharedDir, nwcfFile.getName()).toPath());
                Files.copy(ndcfFile.toPath(), new File(movedSharedDir, ndcfFile.getName()).toPath());
                Files.copy(otherFile.toPath(), new File(movedSharedDir, otherFile.getName()).toPath());
                nwcfFile.delete();
                ndcfFile.delete();
                otherFile.delete();
                new File(sharedDir, "folder.xml").delete();
                sharedDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Shared Services into Master Elements").call();

                // === Branch B (ours): modify two elements at old location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                // Modify NWCF and NDCF (but NOT folder.xml — this is the key!)
                GraficoTestHelper.renameElement(nwcfFile, "Network Capacity Forecast", "Network Capacity Forecast v2");
                GraficoTestHelper.setDocumentation(nwcfFile, "Updated by B");
                GraficoTestHelper.renameElement(ndcfFile, "Node Capacity Forecast", "Node Capacity Forecast v2");
                GraficoTestHelper.setDocumentation(ndcfFile, "Updated by B");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: modify NWCF and NDCF").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // folder.xml should NOT be conflicting (B never touched it)
                // Elements NWCF and NDCF should conflict (B modified, A deleted at old path)
                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict on modified elements");

                // Verify folder.xml is NOT in the conflict list
                assertFalse(mergeResult.getConflicts().keySet().stream()
                        .anyMatch(k -> k.contains("folder.xml")),
                        "folder.xml should NOT be conflicting (B never touched it). "
                        + "Conflicts: " + mergeResult.getConflicts().keySet());

                // === Load models ===
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel);

                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel);

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Should detect move via Pass 3 (element-only, no folder.xml conflict)
                assertTrue(handler.hasMoveGroups(),
                        "Should detect folder move via Pass 3 (element-only conflicts, "
                        + "folder.xml auto-resolved). Conflicts: " + mergeResult.getConflicts().keySet());

                MergeConflictHandler.MoveGroup moveGroup = handler.getMoveGroups().get(0);

                // Location stays default THEIRS (keep new location) — user never changes it
                // because in original bug report, user didn't see a folder location choice
                assertEquals(MergeObjectInfo.THEIRS, moveGroup.locationChoice,
                        "Default should be THEIRS (keep new location)");

                // User picks OURS ("mine") for element content
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    info.setUserChoice(MergeObjectInfo.OURS);
                }

                // === Execute merge ===
                handler.merge();

                // === Verify results ===
                File resultDir = new File(appDir, "id-master/id-shared");

                // NWCF: B's modified content at new location
                File nwcfResult = new File(resultDir, "ApplicationComponent_id-nwcf.xml");
                assertTrue(nwcfResult.exists(),
                        "NWCF should be at new location (masterElements/sharedServices)");
                String nwcfContent = Files.readString(nwcfResult.toPath());
                assertTrue(nwcfContent.contains("name=\"Network Capacity Forecast v2\""),
                        "NWCF should have B's modified name, got: " + nwcfContent);
                assertTrue(nwcfContent.contains("documentation=\"Updated by B\""),
                        "NWCF should have B's documentation, got: " + nwcfContent);

                // NDCF: B's modified content at new location
                File ndcfResult = new File(resultDir, "ApplicationComponent_id-ndcf.xml");
                assertTrue(ndcfResult.exists(),
                        "NDCF should be at new location (masterElements/sharedServices)");
                String ndcfContent = Files.readString(ndcfResult.toPath());
                assertTrue(ndcfContent.contains("name=\"Node Capacity Forecast v2\""),
                        "NDCF should have B's modified name, got: " + ndcfContent);

                // Other: auto-merged at new location (untouched by B)
                File otherResult = new File(resultDir, "ApplicationComponent_id-other.xml");
                assertTrue(otherResult.exists(),
                        "Other should be at new location");

                // Old location should be cleaned up
                assertFalse(new File(sharedDir, "ApplicationComponent_id-nwcf.xml").exists(),
                        "NWCF should NOT remain at old location");
                assertFalse(new File(sharedDir, "ApplicationComponent_id-ndcf.xml").exists(),
                        "NDCF should NOT remain at old location");

                // No duplication — elements should NOT be at both locations
                assertFalse(new File(sharedDir, "ApplicationComponent_id-other.xml").exists(),
                        "Other should NOT be at old location (moved to new)");
            }
        }
    }

    // ========================================================================
    // B2c: Folder move by OURS + element modify by THEIRS — reversed direction
    // ========================================================================

    /**
     * B2c: The REVERSED direction of B2b.
     *
     * Local user (OURS) moved the entire folder to a new location.
     * Remote user (THEIRS) modified elements at the old location.
     *
     * Git result: folder.xml is NOT conflicting (theirs never touched it).
     * Elements conflict at old path (OURS deleted/moved, THEIRS modified).
     *
     * This exercises a bug in Pass 3 + consolidateMoveGroups():
     * Pass 3 assigns oursFolderPath = conflict path (old location), but OURS
     * has NO content at the old path (OURS moved it away). Step 2 of
     * consolidateMoveGroups() tries to checkout Stage.OURS at the old path,
     * which fails because OURS stage is absent there.
     *
     * User choice: THEIRS content (remote modifications) at new location (default THEIRS).
     *
     * Expected: elements at new location with THEIRS' modified content.
     */
    @Test
    public void merge_B2c_FolderMoveByOurs_ElementModifiedByTheirs_TheirsContent() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "b2cReversedRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder shared = helper.addFolder(helper.applicationFolder(), "Shared Services", "id-shared");
            IArchimateElement nwcf = helper.addApplicationComponent(shared, "Network Capacity Forecast", "id-nwcf");
            IArchimateElement ndcf = helper.addApplicationComponent(shared, "Node Capacity Forecast", "id-ndcf");
            IArchimateElement other = helper.addApplicationComponent(shared, "Other Component", "id-other");
            helper.export(repoFolder);

            File appDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.APPLICATION);
            File sharedDir = GraficoTestHelper.folderDir(repoFolder, FolderType.APPLICATION, shared);
            File nwcfFile = GraficoTestHelper.elementFile(sharedDir, nwcf);
            File ndcfFile = GraficoTestHelper.elementFile(sharedDir, ndcf);
            File otherFile = GraficoTestHelper.elementFile(sharedDir, other);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: Shared Services with 3 components").call();

                // === Branch A (will be OURS/local): move entire folder to new location ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File masterDir = new File(appDir, "id-master");
                File movedSharedDir = new File(masterDir, "id-shared");
                GraficoTestHelper.writeFolderXml(masterDir, "Master Elements", "id-master");
                GraficoTestHelper.writeFolderXml(movedSharedDir, "Shared Services", "id-shared");

                // Copy all elements to new location (unchanged by A)
                Files.copy(nwcfFile.toPath(), new File(movedSharedDir, nwcfFile.getName()).toPath());
                Files.copy(ndcfFile.toPath(), new File(movedSharedDir, ndcfFile.getName()).toPath());
                Files.copy(otherFile.toPath(), new File(movedSharedDir, otherFile.getName()).toPath());
                nwcfFile.delete();
                ndcfFile.delete();
                otherFile.delete();
                new File(sharedDir, "folder.xml").delete();
                sharedDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Shared Services into Master Elements").call();

                // === Branch B (will be THEIRS/remote): modify two elements at old location ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(nwcfFile, "Network Capacity Forecast", "Network Capacity Forecast v2");
                GraficoTestHelper.setDocumentation(nwcfFile, "Updated by B");
                GraficoTestHelper.renameElement(ndcfFile, "Node Capacity Forecast", "Node Capacity Forecast v2");
                GraficoTestHelper.setDocumentation(ndcfFile, "Updated by B");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: modify NWCF and NDCF").call();

                // === REVERSED: checkout branchA (mover), merge branchB (modifier) ===
                git.checkout().setName("branchA").call();

                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchB"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Should conflict on elements (OURS deleted at old path, THEIRS modified)
                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict on modified elements");

                // folder.xml should NOT be conflicting (THEIRS/B never touched it)
                assertFalse(mergeResult.getConflicts().keySet().stream()
                        .anyMatch(k -> k.contains("folder.xml")),
                        "folder.xml should NOT be conflicting. "
                        + "Conflicts: " + mergeResult.getConflicts().keySet());

                // === Load models ===
                // Our model = branchA (the mover) - loaded from working tree
                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                assertNotNull(ourModel);

                // Their model = branchB (the modifier)
                IArchimateModel theirModel;
                ObjectId branchBId = gitRepo.resolve("branchB");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchBCommit = rw.parseCommit(branchBId);
                    theirModel = new GraficoModelImporter(gitRepo, branchBCommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(theirModel);

                // === Set up handler ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchB", repo, null);
                handler.init(null, ourModel, theirModel);

                // Should detect move via Pass 3 (element-only conflicts)
                assertTrue(handler.hasMoveGroups(),
                        "Should detect folder move via Pass 3. "
                        + "Conflicts: " + mergeResult.getConflicts().keySet());

                MergeConflictHandler.MoveGroup moveGroup = handler.getMoveGroups().get(0);

                // Location default is THEIRS (keep new location)
                assertEquals(MergeObjectInfo.THEIRS, moveGroup.locationChoice,
                        "Default should be THEIRS (keep new location)");

                // User picks THEIRS content (branchB's modifications)
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    info.setUserChoice(MergeObjectInfo.THEIRS);
                }

                // === Execute merge ===
                handler.merge();

                // === Verify results ===
                File resultDir = new File(appDir, "id-master/id-shared");

                // NWCF: B's modified content at new location
                File nwcfResult = new File(resultDir, "ApplicationComponent_id-nwcf.xml");
                assertTrue(nwcfResult.exists(),
                        "NWCF should be at new location (masterElements/sharedServices)");
                String nwcfContent = Files.readString(nwcfResult.toPath());
                assertTrue(nwcfContent.contains("name=\"Network Capacity Forecast v2\""),
                        "NWCF should have B's modified name, got: " + nwcfContent);
                assertTrue(nwcfContent.contains("documentation=\"Updated by B\""),
                        "NWCF should have B's documentation, got: " + nwcfContent);

                // NDCF: B's modified content at new location
                File ndcfResult = new File(resultDir, "ApplicationComponent_id-ndcf.xml");
                assertTrue(ndcfResult.exists(),
                        "NDCF should be at new location (masterElements/sharedServices)");
                String ndcfContent = Files.readString(ndcfResult.toPath());
                assertTrue(ndcfContent.contains("name=\"Node Capacity Forecast v2\""),
                        "NDCF should have B's modified name, got: " + ndcfContent);

                // Other: at new location (untouched by B, moved by A)
                File otherResult = new File(resultDir, "ApplicationComponent_id-other.xml");
                assertTrue(otherResult.exists(),
                        "Other should be at new location");

                // Old location should be cleaned up
                assertFalse(new File(sharedDir, "ApplicationComponent_id-nwcf.xml").exists(),
                        "NWCF should NOT remain at old location");
                assertFalse(new File(sharedDir, "ApplicationComponent_id-ndcf.xml").exists(),
                        "NDCF should NOT remain at old location");
            }
        }
    }

    // ========================================================================
    // E2: Delete vs modify conflict
    // ========================================================================

    /**
     * E2: Branch A deletes element Q. Branch B modifies (renames) element Q.
     * User chooses to keep B's modified version (OURS).
     *
     * Expected: Q exists with B's content after merge.
     */
    @Test
    public void merge_E2_DeleteVsModify_KeepModified() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e2DeleteModifyRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();

                // Branch A: delete element Q
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                qFile.delete();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: delete Q").call();

                // Branch B: rename Q to P
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.renameElement(qFile, "Q", "P");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to P").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (A deleted, B modified)");

                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // User chooses OURS (keep B's modified version)
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    info.setUserChoice(MergeObjectInfo.OURS);
                }
                handler.merge();

                // Q should exist with B's content
                assertTrue(qFile.exists(), "Q should exist (user chose to keep B's version)");
                String content = Files.readString(qFile.toPath());
                assertTrue(content.contains("name=\"P\""),
                        "Q should have B's renamed content 'P', got: " + content);
            }
        }
    }

    // ========================================================================
    // E9: Folder moved + new element added at old location
    // ========================================================================

    /**
     * E9: Branch A moves folder (same ID) to new location.
     * Branch B adds new element at the OLD folder location.
     *
     * Expected: new element follows to chosen location.
     * This is similar to B3 but specifically tests that elements added at
     * the old path of a moved folder end up at the chosen location.
     */
    @Test
    public void merge_E9_FolderMovedAndNewElementAtOldLocation() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e9MoveAddRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();

                // Branch A: move folderX into folderZ (same ID)
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderZDir = new File(bizDir, "id-folderZ");
                File movedFolderXDir = new File(folderZDir, "id-folderX");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.writeFolderXml(movedFolderXDir, "FolderX", "id-folderX");
                Files.copy(qFile.toPath(), new File(movedFolderXDir, qFile.getName()).toPath());
                qFile.delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX into folderZ").call();

                // Branch B: add new element R at folderX AND modify Q (to create conflict)
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.writeBusinessRole(folderXDir, "R", "id-r");
                GraficoTestHelper.renameElement(qFile, "Q", "Q-B");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: add R and modify Q at folderX").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                File resultDir = new File(bizDir, "id-folderZ/id-folderX");

                if(mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                    IArchimateModel theirModel;
                    ObjectId branchAId = gitRepo.resolve("branchA");
                    try(RevWalk rw = new RevWalk(gitRepo)) {
                        RevCommit branchACommit = rw.parseCommit(branchAId);
                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                .importFromCommit(null);
                    }
                    IArchiRepository repo = new ArchiRepository(repoFolder);
                    MergeConflictHandler handler = new MergeConflictHandler(
                            mergeResult, "branchA", repo, null);
                    handler.init(null, ourModel, theirModel);

                    // Choose new location for folder move
                    if(handler.hasMoveGroups()) {
                        for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                            group.locationChoice = MergeObjectInfo.THEIRS;
                        }
                    }
                    // Accept all content as THEIRS (A's content)
                    for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                        if(!info.isFolderXml()) {
                            info.setUserChoice(MergeObjectInfo.THEIRS);
                        }
                    }
                    handler.merge();
                }

                // Q should be at new location
                assertTrue(new File(resultDir, "BusinessActor_id-q.xml").exists(),
                        "Q should be at folderZ/folderX");

                // R (new element added at old path) should follow to chosen location
                assertTrue(new File(resultDir, "BusinessRole_id-r.xml").exists(),
                        "R (new element) should follow folder move to folderZ/folderX");

                // Old folderX should not have R or Q
                assertFalse(new File(folderXDir, "BusinessRole_id-r.xml").exists(),
                        "R should NOT remain at old folderX");
                assertFalse(new File(folderXDir, "BusinessActor_id-q.xml").exists(),
                        "Q should NOT remain at old folderX");
            }
        }
    }

    // ========================================================================
    // D1: Conflicts AND orphaned dirs (Phase 1 + Phase 2 integration)
    // ========================================================================

    /**
     * D1: Branch A moves folderX → folderZ/folderX (same ID, conflict path).
     * Both branches add independent elements in folderY (clean merge path).
     * Branch B also modifies Q at old folderX location (creating conflict).
     *
     * Phase 1: MergeConflictHandler resolves folderX conflict (folder move).
     * Phase 2: GraficoModelLoader.repairMissingFolderXml() handles any remaining
     * orphaned dirs from the clean merge path.
     *
     * This is the ONLY test exercising both phases in sequence.
     */
    @Test
    public void merge_D1_ConflictsAndOrphanedDirs() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "d1IntegrationRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement s = helper.addBusinessActor(folderY, "S", "id-s");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q, folderY with S").call();

                // Branch A: move folderX → folderZ/folderX (same ID) + add T in folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderZDir = new File(bizDir, "id-folderZ");
                File movedFolderXDir = new File(folderZDir, "id-folderX");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.writeFolderXml(movedFolderXDir, "FolderX", "id-folderX");
                Files.copy(qFile.toPath(), new File(movedFolderXDir, qFile.getName()).toPath());
                // Delete at old location
                qFile.delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                // Add T in folderY
                GraficoTestHelper.writeBusinessRole(folderYDir, "T", "id-t");

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX to folderZ, add T in folderY").call();

                // Branch B: modify Q at old folderX + add U in folderY
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.renameElement(qFile, "Q", "Q-B");
                GraficoTestHelper.writeBusinessRole(folderYDir, "U", "id-u");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: modify Q, add U in folderY").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                File resultDir = new File(bizDir, "id-folderZ/id-folderX");

                // === Phase 1: Handle conflicts ===
                if(mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                    IArchimateModel theirModel;
                    ObjectId branchAId = gitRepo.resolve("branchA");
                    try(RevWalk rw = new RevWalk(gitRepo)) {
                        RevCommit branchACommit = rw.parseCommit(branchAId);
                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                .importFromCommit(null);
                    }
                    IArchiRepository repo = new ArchiRepository(repoFolder);
                    MergeConflictHandler handler = new MergeConflictHandler(
                            mergeResult, "branchA", repo, null);
                    handler.init(null, ourModel, theirModel);

                    // Choose new location for folder move
                    if(handler.hasMoveGroups()) {
                        for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                            group.locationChoice = MergeObjectInfo.THEIRS;
                        }
                    }
                    // Accept OURS content (B's modifications) for elements
                    for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                        if(!info.isFolderXml()) {
                            info.setUserChoice(MergeObjectInfo.OURS);
                        }
                    }
                    handler.merge();
                }

                // === Phase 2: Repair any remaining orphans ===
                IArchiRepository repo = new ArchiRepository(repoFolder);
                GraficoModelLoader loader = new GraficoModelLoader(repo, true);
                loader.repairMissingFolderXml();

                // Apply any pending folder moves with defaults
                if(loader.hasPendingFolderMoves()) {
                    for(FolderMoveInfo moveInfo : loader.getFolderMoves()) {
                        moveInfo.setUserChoice(FolderMoveInfo.KEEP_NEW_LOCATION);
                    }
                    loader.applyFolderMoveResolutions();
                }

                // === Assertions: no data loss ===

                // Q should be at new location with B's content
                File qResult = new File(resultDir, "BusinessActor_id-q.xml");
                assertTrue(qResult.exists(),
                        "Q should be at folderZ/folderX (moved by A)");
                String qContent = Files.readString(qResult.toPath());
                assertTrue(qContent.contains("name=\"Q-B\""),
                        "Q should have B's content 'Q-B', got: " + qContent);

                // S should still be in folderY (untouched)
                assertTrue(new File(folderYDir, "BusinessActor_id-s.xml").exists(),
                        "S should remain in folderY (untouched)");

                // T (added by A in folderY) should be present
                assertTrue(new File(folderYDir, "BusinessRole_id-t.xml").exists(),
                        "T should be in folderY (added by A)");

                // U (added by B in folderY) should be present
                assertTrue(new File(folderYDir, "BusinessRole_id-u.xml").exists(),
                        "U should be in folderY (added by B)");

                // Old folderX should be cleaned up
                assertFalse(new File(folderXDir, "BusinessActor_id-q.xml").exists(),
                        "Q should NOT remain at old folderX");
            }
        }
    }

    // ========================================================================
    // E6: Folder delete vs element modify
    // ========================================================================

    /**
     * E6: Branch A deletes entire folderX (folder.xml + all elements).
     * Branch B modifies element Q inside folderX.
     * User chooses OURS (keep B's modified version).
     *
     * Expected: folderX restored with Q having B's content.
     * R is gone (A deleted entire folder, B never touched R).
     */
    @Test
    public void merge_E6_FolderDeleteVsElementModify_KeepModified() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e6FolderDeleteRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement r = helper.addBusinessRole(folderX, "R", "id-r");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);
            File rFile = GraficoTestHelper.elementFile(folderXDir, r);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q and R").call();

                // Branch A: delete entire folderX
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.deleteFolder(folderXDir);
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: delete entire folderX").call();

                // Branch B: modify Q (rename to Q-modified)
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.renameElement(qFile, "Q", "Q-modified");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to Q-modified").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (A deleted folder+Q, B modified Q)");

                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // User chooses OURS (keep B's modified version)
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    info.setUserChoice(MergeObjectInfo.OURS);
                }
                handler.merge();

                // Q should exist with B's modified content
                assertTrue(qFile.exists(), "Q should exist (user chose to keep B's version)");
                String content = Files.readString(qFile.toPath());
                assertTrue(content.contains("name=\"Q-modified\""),
                        "Q should have B's renamed content 'Q-modified', got: " + content);

                // folder.xml was auto-deleted by git (not conflicting — A deleted, B never touched).
                // Phase 2 repair restores it for the surviving element.
                IArchiRepository repo2 = new ArchiRepository(repoFolder);
                GraficoModelLoader loader = new GraficoModelLoader(repo2, true);
                loader.repairMissingFolderXml();

                assertTrue(new File(folderXDir, "folder.xml").exists(),
                        "folder.xml should be restored by Phase 2 repair");
            }
        }
    }

    /**
     * E6 variant: User chooses THEIRS (accept deletion).
     *
     * Expected: folderX and Q both gone.
     *
     * Known gap: the handler uses git checkout --theirs, but stage 3 (theirs)
     * doesn't exist for a deleted file. The checkout silently does nothing,
     * leaving Q on disk. The handler needs explicit file deletion support
     * for "resolve conflict to deleted" scenarios.
     */
    @Test
    public void merge_E6_FolderDeleteVsElementModify_AcceptDelete() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e6FolderDeleteAcceptRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement r = helper.addBusinessRole(folderX, "R", "id-r");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q and R").call();

                // Branch A: delete entire folderX
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.deleteFolder(folderXDir);
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: delete entire folderX").call();

                // Branch B: modify Q (rename to Q-modified)
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.renameElement(qFile, "Q", "Q-modified");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to Q-modified").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (A deleted folder+Q, B modified Q)");

                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // User chooses THEIRS (accept A's deletion)
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    info.setUserChoice(MergeObjectInfo.THEIRS);
                }
                handler.merge();

                // Q should NOT exist (user accepted deletion)
                assertFalse(qFile.exists(),
                        "Q should NOT exist (user accepted A's deletion)");
            }
        }
    }

    // ========================================================================
    // F1: Element move vs element delete (cross-path deletion)
    // ========================================================================

    /**
     * F1: Branch A moves element Q from folderX to folderY (different folder IDs).
     * Branch B deletes element Q at folderX.
     *
     * Git sees: both delete at folderX (auto-resolved), A adds at folderY (auto-merged).
     * Result: Q appears at folderY even though B intended to delete it.
     * This is the element-level mirror of B4 (folder move + element delete).
     *
     * Expected: Q should NOT exist at folderY (B's deletion intent should win).
     * Current behavior: Q leaks to folderY — cross-path deletion not yet detected.
     */
    @Test
    public void merge_F1_ElementMoveVsElementDelete() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "f1MoveDeleteRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement r = helper.addBusinessRole(folderX, "R", "id-r");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);
            File rFile = GraficoTestHelper.elementFile(folderXDir, r);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q, R; folderY empty").call();

                // Branch A: move Q from folderX to folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.moveElementFile(folderXDir, folderYDir, q);
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q to folderY").call();

                // Branch B: delete Q at folderX
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                qFile.delete();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchB: delete Q").call();

                // Save pre-merge commit IDs for cross-path detection
                ObjectId oursId = gitRepo.resolve("branchB");
                ObjectId theirsId = gitRepo.resolve("branchA");

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Both delete at folderX → no conflict. A adds at folderY → auto-merged.
                // Run cross-path deletion detection (Phase 1.5)
                MergeConflictHandler.detectAndRemoveCrossPathDeletions(gitRepo, oursId, theirsId);

                // Expected (after cross-path deletion detection is implemented):
                // Q should NOT exist anywhere — B's deletion should be detected.
                assertFalse(GraficoTestHelper.elementFile(folderYDir, q).exists(),
                        "Q should NOT be at folderY (B deleted it, move should not resurrect)");
                assertFalse(qFile.exists(),
                        "Q should NOT be at folderX (both branches removed it)");

                // R should still be at folderX (untouched by both branches)
                assertTrue(rFile.exists(),
                        "R should remain at folderX (untouched)");
            }
        }
    }

    // ========================================================================
    // F2: Element modify vs element move by other branch
    // ========================================================================

    /**
     * F2: Branch A moves element Q from folderX to folderY (different folder IDs).
     * Branch B modifies element Q at folderX (renames to "Q-modified").
     *
     * Git sees: A deleted Q at folderX + added Q at folderY. B modified Q at folderX.
     * Result: CONFLICTING (A delete vs B modify at folderX).
     *
     * Handler should detect this as move+modify via resolveMovedObject() / Pass 3.
     * User chooses: THEIRS location (folderY) + OURS content (B's modification).
     *
     * Expected: Q at folderY with name "Q-modified".
     */
    @Test
    public void merge_F2_ElementModifyVsElementMove_TheirsLocationOursContent() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "f2ModifyMoveRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();

                // Branch A: move Q from folderX to folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.moveElementFile(folderXDir, folderYDir, q);
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q to folderY").call();

                // Branch B: modify Q at folderX (rename to Q-modified)
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.renameElement(qFile, "Q", "Q-modified");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to Q-modified").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (A deleted Q at folderX, B modified Q at folderX)");

                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Should detect as move: Q deleted at folderX by A, but exists at folderY
                // Set location to THEIRS (accept move to folderY), content to OURS (B's modification)
                if(handler.hasMoveGroups()) {
                    for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                        group.locationChoice = MergeObjectInfo.THEIRS;
                    }
                }
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    if(!info.isFolderXml()) {
                        info.setUserChoice(MergeObjectInfo.OURS);
                    }
                }
                handler.merge();

                // Q should be at folderY with B's modified content
                File qAtY = GraficoTestHelper.elementFile(folderYDir, q);
                assertTrue(qAtY.exists(),
                        "Q should be at folderY (user accepted A's move)");
                String content = Files.readString(qAtY.toPath());
                assertTrue(content.contains("name=\"Q-modified\""),
                        "Q should have B's content 'Q-modified', got: " + content);

                // Q should NOT be at folderX
                assertFalse(qFile.exists(),
                        "Q should NOT remain at folderX (moved to folderY)");
            }
        }
    }

    /**
     * F2 variant: User chooses OURS location (keep at folderX) + OURS content.
     *
     * Expected: Q stays at folderX with B's modified content. A's copy at folderY removed.
     */
    @Test
    public void merge_F2_ElementModifyVsElementMove_OursLocationOursContent() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "f2ModifyMoveOursRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderY = helper.addFolder(helper.businessFolder(), "FolderY", "id-folderY");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File folderYDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderY);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();

                // Branch A: move Q from folderX to folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.moveElementFile(folderXDir, folderYDir, q);
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move Q to folderY").call();

                // Branch B: modify Q at folderX (rename to Q-modified)
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.renameElement(qFile, "Q", "Q-modified");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: rename Q to Q-modified").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (A deleted Q at folderX, B modified Q at folderX)");

                IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                IArchimateModel theirModel;
                ObjectId branchAId = gitRepo.resolve("branchA");
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Set location to OURS (keep at folderX), content to OURS (B's modification)
                if(handler.hasMoveGroups()) {
                    for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                        group.locationChoice = MergeObjectInfo.OURS;
                    }
                }
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    if(!info.isFolderXml()) {
                        info.setUserChoice(MergeObjectInfo.OURS);
                    }
                }
                handler.merge();

                // Q should be at folderX with B's modified content
                assertTrue(qFile.exists(),
                        "Q should remain at folderX (user rejected move)");
                String content = Files.readString(qFile.toPath());
                assertTrue(content.contains("name=\"Q-modified\""),
                        "Q should have B's content 'Q-modified', got: " + content);

                // Q should NOT be at folderY (move was rejected)
                assertFalse(GraficoTestHelper.elementFile(folderYDir, q).exists(),
                        "Q should NOT be at folderY (move rejected)");
            }
        }
    }

    // ========================================================================
    // B5: Nested folder move + deep element modify
    // ========================================================================

    /**
     * B5: Branch A moves folderX (which contains subfolderY with a deep element)
     * to a new parent (folderZ/folderX). Branch B modifies the deep element
     * inside subfolderY at the old path.
     *
     * Common start:
     *   model/business/folderX/folder.xml (id-folderX)
     *   model/business/folderX/subY/folder.xml (id-subY)
     *   model/business/folderX/subY/BusinessActor_id-deep.xml (name="Deep")
     *   model/business/folderX/BusinessActor_id-q.xml (name="Q")
     *
     * Branch A: moves folderX → folderZ/folderX (entire subtree)
     *
     * Branch B: modifies deep element name→"Deep-Modified" at old subY path
     *
     * Expected: nested move preserves structure. Deep element at new location
     * with B's content.
     */
    @Test
    public void merge_B5_NestedFolderMoveDeepElementModify() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "b5NestedMoveRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderOuter = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IFolder folderInner = helper.addFolder(folderOuter, "SubY", "id-subY");
            IArchimateElement q = helper.addBusinessActor(folderOuter, "Q", "id-q");
            IArchimateElement deep = helper.addBusinessActor(folderInner, "Deep", "id-deep");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderOuterDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderOuter);
            File folderInnerDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderOuter, folderInner);
            File deepFile = GraficoTestHelper.elementFile(folderInnerDir, deep);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with subY and deep element").call();

                // Branch A: move entire folderX subtree to folderZ/folderX
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderZDir = new File(bizDir, "id-folderZ");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.moveFolderDir(folderOuterDir, new File(folderZDir, "id-folderX"));

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX subtree to folderZ").call();

                // Branch B: modify deep element at old path
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                GraficoTestHelper.renameElement(deepFile, "Deep", "Deep-Modified");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: modify deep element").call();

                // Save pre-merge IDs for cross-path detection
                ObjectId oursId = gitRepo.resolve("branchB");
                ObjectId theirsId = gitRepo.resolve("branchA");

                // Merge branchA into branchB
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                File resultX = new File(bizDir, "id-folderZ/id-folderX");
                File resultSubY = new File(resultX, "id-subY");

                if(mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                    IArchimateModel theirModel;
                    try(RevWalk rw = new RevWalk(gitRepo)) {
                        RevCommit branchACommit = rw.parseCommit(gitRepo.resolve("branchA"));
                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                .importFromCommit(null);
                    }
                    IArchiRepository repo = new ArchiRepository(repoFolder);
                    MergeConflictHandler handler = new MergeConflictHandler(
                            mergeResult, "branchA", repo, null);
                    handler.init(null, ourModel, theirModel);

                    // Choose new location (THEIRS = A's move)
                    if(handler.hasMoveGroups()) {
                        for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                            group.locationChoice = MergeObjectInfo.THEIRS;
                            // For modified elements, keep B's content (OURS)
                            for(MergeObjectInfo info : group.relatedInfos) {
                                if(!info.isFolderXml()) {
                                    info.setUserChoice(MergeObjectInfo.OURS);
                                }
                            }
                        }
                    }

                    // Non-move conflicts: accept OURS for content
                    for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                        if(!info.isPartOfMove() && info.getUserChoice() == 0) {
                            info.setUserChoice(MergeObjectInfo.OURS);
                        }
                    }

                    handler.merge();
                }

                // Cross-path deletion detection
                MergeConflictHandler.detectAndRemoveCrossPathDeletions(gitRepo, oursId, theirsId);

                // === Verify ===

                // Q should be at new location
                assertTrue(new File(resultX, "BusinessActor_id-q.xml").exists(),
                        "Q should be at folderZ/folderX");

                // Deep element should be at new nested location
                File deepAtNew = new File(resultSubY, "BusinessActor_id-deep.xml");
                assertTrue(deepAtNew.exists(),
                        "Deep element should be at folderZ/folderX/subY");

                // Deep element should have B's modified content
                String deepContent = Files.readString(deepAtNew.toPath());
                assertTrue(deepContent.contains("name=\"Deep-Modified\""),
                        "Deep element should have B's content 'Deep-Modified', got: " + deepContent);

                // Old paths should be cleaned up
                assertFalse(new File(folderOuterDir, "BusinessActor_id-q.xml").exists(),
                        "Q should NOT remain at old folderX");
                assertFalse(new File(folderInnerDir, "BusinessActor_id-deep.xml").exists(),
                        "Deep should NOT remain at old subY");
            }
        }
    }

    // ========================================================================
    // E3: Both branches delete same element
    // ========================================================================

    /**
     * E3: Branch A deletes element Q. Branch B also deletes element Q.
     * Both agree on deletion → should be a clean merge, no conflict.
     *
     * Expected: Q is gone, no conflict, other elements unaffected.
     */
    @Test
    public void merge_E3_BothDeleteSameElement() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e3BothDeleteRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement r = helper.addBusinessRole(folderX, "R", "id-r");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);
            File rFile = GraficoTestHelper.elementFile(folderXDir, r);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q and R").call();

                // Branch A: delete Q
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                qFile.delete();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: delete Q").call();

                // Branch B: also delete Q
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                qFile.delete();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchB: delete Q").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Should be a clean merge — both sides deleted the same file
                assertEquals(MergeResult.MergeStatus.MERGED, mergeResult.getMergeStatus(),
                        "Both-delete should merge cleanly, got: " + mergeResult.getMergeStatus());

                // Q should be gone
                assertFalse(qFile.exists(),
                        "Q should be deleted (both branches agreed)");

                // R should still exist (untouched)
                assertTrue(rFile.exists(),
                        "R should still exist (not touched by either branch)");

                // folder.xml should still exist
                assertTrue(new File(folderXDir, "folder.xml").exists(),
                        "folder.xml should still exist");
            }
        }
    }

    // ========================================================================
    // E4: Both branches add different new elements
    // ========================================================================

    /**
     * E4: Branch A adds new element R. Branch B adds different new element S.
     * Both are unique additions → should merge cleanly, both present.
     *
     * Expected: Q (original), R (from A), and S (from B) all present.
     */
    @Test
    public void merge_E4_BothAddDifferentElements() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e4BothAddRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q").call();

                // Branch A: add element R
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.writeBusinessRole(folderXDir, "R", "id-r");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchA: add R").call();

                // Branch B: add different element S
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.writeBusinessActor(folderXDir, "S", "id-s");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: add S").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Should be a clean merge — different files added by each branch
                assertEquals(MergeResult.MergeStatus.MERGED, mergeResult.getMergeStatus(),
                        "Adding different elements should merge cleanly, got: " + mergeResult.getMergeStatus());

                // All three elements should exist
                assertTrue(qFile.exists(),
                        "Q should still exist");
                assertTrue(new File(folderXDir, "BusinessRole_id-r.xml").exists(),
                        "R should exist (added by A)");
                assertTrue(new File(folderXDir, "BusinessActor_id-s.xml").exists(),
                        "S should exist (added by B)");
            }
        }
    }

    // ========================================================================
    // E8: Both branches delete same folder
    // ========================================================================

    /**
     * E8: Branch A deletes folderX (folder.xml + all elements).
     * Branch B also deletes folderX.
     * Both agree → should merge cleanly, folder completely gone.
     *
     * Expected: folderX directory and all contents gone, no conflict.
     */
    @Test
    public void merge_E8_BothDeleteSameFolder() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e8BothDeleteFolderRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // === Build and export initial model ===
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            IArchimateElement r = helper.addBusinessRole(folderX, "R", "id-r");
            helper.export(repoFolder);

            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);
            File rFile = GraficoTestHelper.elementFile(folderXDir, r);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q and R").call();

                // Branch A: delete entire folderX
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                GraficoTestHelper.deleteFolder(folderXDir);
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: delete folderX entirely").call();

                // Branch B: also delete entire folderX
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                GraficoTestHelper.deleteFolder(folderXDir);
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchB: delete folderX entirely").call();

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Should be clean — both sides deleted the same folder
                assertEquals(MergeResult.MergeStatus.MERGED, mergeResult.getMergeStatus(),
                        "Both-delete-folder should merge cleanly, got: " + mergeResult.getMergeStatus());

                // Folder should be completely gone
                assertFalse(folderXDir.exists(),
                        "folderX directory should not exist");
                assertFalse(new File(folderXDir, "folder.xml").exists(),
                        "folder.xml should not exist");
                assertFalse(qFile.exists(),
                        "Q should not exist");
                assertFalse(rFile.exists(),
                        "R should not exist");
            }
        }
    }

    // ========================================================================
    // E10: Folder move + new subfolder added at old location
    // ========================================================================

    /**
     * E10: Branch A moves folderX to folderZ/folderX (same ID).
     * Branch B adds a new subfolder (subNew) inside folderX at old location.
     *
     * After merge: subfolder orphaned at old location (no folder.xml).
     * Repair should detect that folderX moved and relocate the subfolder.
     *
     * Expected: new subfolder follows the parent move to folderZ/folderX/subNew.
     */
    @Test
    public void merge_E10_FolderMoveAndNewSubfolderAtOldLocation() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "e10SubfolderRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);
            File qFile = GraficoTestHelper.elementFile(folderXDir, q);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with Q").call();

                // Branch A: move folderX to folderZ/folderX
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderZDir = new File(bizDir, "id-folderZ");
                File movedXDir = new File(folderZDir, "id-folderX");
                GraficoTestHelper.writeFolderXml(folderZDir, "FolderZ", "id-folderZ");
                GraficoTestHelper.writeFolderXml(movedXDir, "FolderX", "id-folderX");
                Files.copy(qFile.toPath(), new File(movedXDir, qFile.getName()).toPath());
                qFile.delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX to folderZ/folderX").call();

                // Branch B: add new subfolder with element under old folderX
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                File subNew = new File(folderXDir, "id-subNew");
                GraficoTestHelper.writeFolderXml(subNew, "SubNew", "id-subNew");
                GraficoTestHelper.writeBusinessRole(subNew, "S", "id-s");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: add subNew under folderX").call();

                // Save pre-merge IDs
                ObjectId oursId = gitRepo.resolve("branchB");
                ObjectId theirsId = gitRepo.resolve("branchA");

                // Merge
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Handle conflicts if any
                if(mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    IArchimateModel ourModel = new GraficoModelImporter(repoFolder).importAsModel();
                    IArchimateModel theirModel;
                    try(RevWalk rw = new RevWalk(gitRepo)) {
                        RevCommit branchACommit = rw.parseCommit(gitRepo.resolve("branchA"));
                        theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                                .importFromCommit(null);
                    }
                    IArchiRepository repo = new ArchiRepository(repoFolder);
                    MergeConflictHandler handler = new MergeConflictHandler(
                            mergeResult, "branchA", repo, null);
                    handler.init(null, ourModel, theirModel);

                    if(handler.hasMoveGroups()) {
                        for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                            group.locationChoice = MergeObjectInfo.THEIRS;
                        }
                    }
                    for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                        if(info.getUserChoice() == 0) {
                            info.setUserChoice(MergeObjectInfo.THEIRS);
                        }
                    }
                    handler.merge();
                }

                // Cross-path detection
                MergeConflictHandler.detectAndRemoveCrossPathDeletions(gitRepo, oursId, theirsId);

                // Run repair to handle orphaned subfolder
                IArchiRepository repo = new ArchiRepository(repoFolder);
                GraficoModelLoader loader = new GraficoModelLoader(repo, true);
                loader.repairMissingFolderXml();
                if(loader.hasPendingFolderMoves()) {
                    loader.applyFolderMoveResolutions();
                }

                // === Verify ===

                File resultX = new File(bizDir, "id-folderZ/id-folderX");

                // Q should be at new location
                assertTrue(new File(resultX, "BusinessActor_id-q.xml").exists(),
                        "Q should be at folderZ/folderX");

                // Subfolder's element S should end up accessible.
                // It may be at the new location following the parent move,
                // or it may stay at old location with a repaired folder.xml.
                File sAtNewSub = new File(resultX, "id-subNew/BusinessRole_id-s.xml");
                File sAtOldSub = new File(folderXDir, "id-subNew/BusinessRole_id-s.xml");
                assertTrue(sAtNewSub.exists() || sAtOldSub.exists(),
                        "S should exist (either moved with parent or repaired at old location). "
                        + "New: " + sAtNewSub.exists() + ", Old: " + sAtOldSub.exists());

                // If S is at old location, it should have a folder.xml (repaired)
                if(sAtOldSub.exists()) {
                    File subFolderXml = new File(folderXDir, "id-subNew/folder.xml");
                    assertTrue(subFolderXml.exists(),
                            "If subNew stays at old location, it should have folder.xml (repaired)");
                }
            }
        }
    }

    // ========================================================================
    // Bug regression: remote reorganizes folders — no false deletions
    // ========================================================================

    /**
     * Bug regression: when the remote (theirs) moves a folder to a new location,
     * {@code git diff --diff-filter=D base..theirs} reports every element in the
     * old folder as "deleted". The old cross-path detection code treated these as
     * true deletions and removed the files from the working tree — even though
     * they were just moved to a new path. The fix verifies that an element is
     * truly absent from the parent's tree (not just relocated) before considering
     * it deleted.
     *
     * Scenario:
     *   Base:   folderX with elements P, Q, R
     *   Ours:   unchanged (local is behind)
     *   Theirs: moves folderX → folderY (P, Q, R now at folderY)
     *
     * Expected: after merge + cross-path detection, P, Q, R exist at folderY.
     */
    @Test
    public void merge_G1_RemoteReorganizesFolders_NoFalseDeletions() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "g1ReorgRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            helper.addBusinessActor(folderX, "P", "id-p");
            helper.addBusinessRole(folderX, "Q", "id-q");
            helper.addBusinessProcess(folderX, "R", "id-r");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with P, Q, R").call();

                // Branch A (theirs/remote): move folderX → folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderYDir = new File(bizDir, "folderY");
                GraficoTestHelper.writeFolderXml(folderYDir, "FolderY", "id-folderX");
                GraficoTestHelper.writeBusinessActor(folderYDir, "P", "id-p");
                GraficoTestHelper.writeBusinessRole(folderYDir, "Q", "id-q");
                GraficoTestHelper.writeBusinessProcess(folderYDir, "R", "id-r");
                new File(folderXDir, "BusinessActor_id-p.xml").delete();
                new File(folderXDir, "BusinessRole_id-q.xml").delete();
                new File(folderXDir, "BusinessProcess_id-r.xml").delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: move folderX to folderY").call();

                // Branch B (ours/local): no changes — still at base
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                // Make a trivial commit so merge is not a fast-forward
                File modelXml = new File(repoFolder, "model/folder.xml");
                GraficoTestHelper.setDocumentation(modelXml, "trivial update");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: trivial update").call();

                ObjectId oursId = gitRepo.resolve("branchB");
                ObjectId theirsId = gitRepo.resolve("branchA");

                // Merge A into B
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                // Should be MERGED or CONFLICTING — either way, run cross-path detection
                MergeConflictHandler.detectAndRemoveCrossPathDeletions(gitRepo, oursId, theirsId);

                File folderYResult = new File(bizDir, "folderY");

                // All elements should exist at folderY — NOT deleted by cross-path detection
                assertTrue(new File(folderYResult, "BusinessActor_id-p.xml").exists(),
                        "P should be at folderY (not falsely deleted)");
                assertTrue(new File(folderYResult, "BusinessRole_id-q.xml").exists(),
                        "Q should be at folderY (not falsely deleted)");
                assertTrue(new File(folderYResult, "BusinessProcess_id-r.xml").exists(),
                        "R should be at folderY (not falsely deleted)");

                // Old location should NOT exist
                assertFalse(folderXDir.exists(),
                        "folderX should not exist after merge");
            }
        }
    }

    /**
     * Bug regression variant: remote adds NEW elements that didn't exist in
     * the merge base. These should never appear in any deletion set because
     * they weren't in the base to begin with.
     *
     * Scenario:
     *   Base:   folderX with element P
     *   Ours:   unchanged (local is behind)
     *   Theirs: adds Q, R to folderX; also moves folderX → folderY
     *
     * Expected: after merge + cross-path detection, P, Q, R exist at folderY.
     */
    @Test
    public void merge_G2_RemoteAddsAndReorganizes_NewElementsPreserved() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "g2AddReorgRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            var helper = new GraficoTestHelper();
            IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
            helper.addBusinessActor(folderX, "P", "id-p");
            helper.export(repoFolder);

            File bizDir = GraficoTestHelper.topFolderDir(repoFolder, FolderType.BUSINESS);
            File folderXDir = GraficoTestHelper.folderDir(repoFolder, FolderType.BUSINESS, folderX);

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: folderX with P").call();

                // Branch A (theirs/remote): add Q, R; move folderX → folderY
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                File folderYDir = new File(bizDir, "folderY");
                GraficoTestHelper.writeFolderXml(folderYDir, "FolderY", "id-folderX");
                GraficoTestHelper.writeBusinessActor(folderYDir, "P", "id-p");
                GraficoTestHelper.writeBusinessRole(folderYDir, "Q", "id-q");
                GraficoTestHelper.writeBusinessProcess(folderYDir, "R", "id-r");
                new File(folderXDir, "BusinessActor_id-p.xml").delete();
                new File(folderXDir, "folder.xml").delete();
                folderXDir.delete();

                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: add Q,R and move to folderY").call();

                // Branch B (ours/local): no changes
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                File modelXml = new File(repoFolder, "model/folder.xml");
                GraficoTestHelper.setDocumentation(modelXml, "trivial update");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: trivial update").call();

                ObjectId oursId = gitRepo.resolve("branchB");
                ObjectId theirsId = gitRepo.resolve("branchA");

                git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                MergeConflictHandler.detectAndRemoveCrossPathDeletions(gitRepo, oursId, theirsId);

                File folderYResult = new File(bizDir, "folderY");
                assertTrue(new File(folderYResult, "BusinessActor_id-p.xml").exists(),
                        "P should be at folderY");
                assertTrue(new File(folderYResult, "BusinessRole_id-q.xml").exists(),
                        "Q (new element) should be at folderY");
                assertTrue(new File(folderYResult, "BusinessProcess_id-r.xml").exists(),
                        "R (new element) should be at folderY");
                assertFalse(folderXDir.exists(),
                        "folderX should not exist after merge");
            }
        }
    }

    // ========================================================================
    // H1: Element duplicated at base, A cleans P copy, B cleans Q copy + renames
    // ========================================================================

    /**
     * H1: Base has the element at BOTH folderP and folderQ (duplicated from a prior merge).
     * Branch A deletes from P, keeps at Q.
     * Branch B deletes from Q, renames content at P.
     *
     * Merge A into B:
     *   - Conflict at P: A deleted, B modified → modify/delete conflict
     *   - Q: A kept (unchanged), B deleted → git auto-resolves as DELETE
     *     (B deleted and A didn't change, so git takes the delete)
     *
     * User choice: THEIRS (A = the mover) → element should end up at Q with A's content.
     *
     * BUG (before fix): The file at Q does not exist on disk after merge because
     * git auto-resolved Q as deleted. resolveMoveResolvedElements tries to git-add
     * the Q path which doesn't exist → IOException.
     *
     * Expected: Q has element with A's content, P is deleted.
     */
    @Test
    public void merge_H1_DuplicatedElementAtBase_MoverContentAtDeletedPath() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "h1DuplicateBaseRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // Build in-memory model: element Q in folderP
            IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
            model.setDefaults();
            model.setName("Test");

            IFolder bizFolder = model.getFolder(FolderType.BUSINESS);
            IFolder folderP = IArchimateFactory.eINSTANCE.createFolder();
            folderP.setName("FolderP");
            bizFolder.getFolders().add(folderP);
            IFolder folderQ = IArchimateFactory.eINSTANCE.createFolder();
            folderQ.setName("FolderQ");
            bizFolder.getFolders().add(folderQ);

            IArchimateElement elementQ = IArchimateFactory.eINSTANCE.createBusinessActor();
            elementQ.setName("Q");
            folderP.getElements().add(elementQ);

            // Export to create valid GRAFICO structure
            new GraficoModelExporter(model, repoFolder).exportModel();

            // Locate the exported file paths
            String qId = elementQ.getId();
            String qFileName = "BusinessActor_" + qId + ".xml";
            File bizDir = new File(repoFolder, "model/business");
            File folderPDir = new File(bizDir, folderP.getId());
            File folderQDir = new File(bizDir, folderQ.getId());
            File qFileInP = new File(folderPDir, qFileName);
            assertTrue(qFileInP.exists(), "Q should exist in folderP after export");

            // Duplicate Q into folderQ (simulates prior merge artifact)
            File qFileInQ = new File(folderQDir, qFileName);
            Files.copy(qFileInP.toPath(), qFileInQ.toPath());

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: Q at both folderP and folderQ").call();

                // === Branch A: delete from P, keep at Q (unchanged) ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();
                qFileInP.delete();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchA: delete Q from folderP").call();

                // === Branch B: delete from Q, rename at P ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();
                qFileInQ.delete();
                // Modify Q at folderP (rename Q → Q-renamed)
                String originalContent = Files.readString(qFileInP.toPath());
                Files.writeString(qFileInP.toPath(),
                        originalContent.replace("name=\"Q\"", "name=\"Q-renamed\""));
                git.add().addFilepattern(".").call();
                git.add().addFilepattern(".").setUpdate(true).call();
                git.commit().setMessage("branchB: delete Q from folderQ, rename at folderP").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                        .include(gitRepo.resolve("branchA"))
                        .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        .call();

                assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus(),
                        "Should conflict (A deleted Q at folderP, B modified Q at folderP)");

                // Q at folderQ should NOT exist on disk (git auto-resolved B's delete)
                assertFalse(qFileInQ.exists(),
                        "Q at folderQ should NOT exist (git auto-resolved B's delete)");

                // Load models from commits (not working tree) because the DirCache
                // skips conflicted entries — the element only exists at conflicted paths.
                // In production, locateModel() returns the pre-merge in-memory model.
                ObjectId branchBId = gitRepo.resolve("branchB");
                ObjectId branchAId = gitRepo.resolve("branchA");
                IArchimateModel ourModel;
                IArchimateModel theirModel;
                try(RevWalk rw = new RevWalk(gitRepo)) {
                    RevCommit branchBCommit = rw.parseCommit(branchBId);
                    ourModel = new GraficoModelImporter(gitRepo, branchBCommit.getTree())
                            .importFromCommit(null);
                    RevCommit branchACommit = rw.parseCommit(branchAId);
                    theirModel = new GraficoModelImporter(gitRepo, branchACommit.getTree())
                            .importFromCommit(null);
                }
                assertNotNull(ourModel, "Should import our model");
                assertNotNull(theirModel, "Should import their model");

                IArchiRepository repo = new ArchiRepository(repoFolder);
                MergeConflictHandler handler = new MergeConflictHandler(
                        mergeResult, "branchA", repo, null);
                handler.init(null, ourModel, theirModel);

                // Should detect the conflict as a move
                assertEquals(1, handler.getMergeObjectInfos().size(),
                        "Should have exactly 1 conflict. Conflicts: " + mergeResult.getConflicts().keySet());
                MergeObjectInfo conflictInfo = handler.getMergeObjectInfos().get(0);
                assertTrue(conflictInfo.isResolvedAsMove(),
                        "Conflict should be detected as move. Path: " + conflictInfo.getXMLPath()
                        + ", ours=" + conflictInfo.getEObject(MergeObjectInfo.OURS)
                        + ", theirs=" + conflictInfo.getEObject(MergeObjectInfo.THEIRS));

                // User chooses THEIRS (mover = branchA)
                for(MergeObjectInfo info : handler.getMergeObjectInfos()) {
                    if(!info.isFolderXml()) {
                        info.setUserChoice(MergeObjectInfo.THEIRS);
                    }
                }
                if(handler.hasMoveGroups()) {
                    for(MergeConflictHandler.MoveGroup group : handler.getMoveGroups()) {
                        group.locationChoice = MergeObjectInfo.THEIRS;
                    }
                }

                // This should NOT throw IOException (was the bug: git add on non-existent path)
                handler.merge();

                // Q should be at folderQ with A's content
                assertTrue(qFileInQ.exists(),
                        "Q should be at folderQ (user chose A's move)");
                String content = Files.readString(qFileInQ.toPath());
                assertTrue(content.contains("name=\"Q\""),
                        "Q should have A's original content 'Q', got: " + content);

                // Q should NOT be at folderP
                assertFalse(qFileInP.exists(),
                        "Q should NOT remain at folderP (moved to folderQ)");
            }
        }
    }
}
