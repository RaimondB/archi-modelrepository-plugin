/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.archicontribs.modelrepository.GitHelper;
import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.model.ModelChecker;
import com.archimatetool.editor.utils.FileUtils;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;


@SuppressWarnings("nls")
public class GraficoModelLoaderTests {

    @BeforeEach
    public void runOnceBeforeEachTest() {
    }

    @AfterEach
    public void runOnceAfterEachTest() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }

    // ========================================================================
    // extractIdFromFolderXml tests
    // ========================================================================

    @Test
    public void extractIdFromFolderXml_ReturnsId() {
        byte[] content = "<archimate:Folder id=\"id-abc123\" name=\"Test\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("id-abc123", GraficoModelLoader.extractIdFromFolderXml(content));
    }

    @Test
    public void extractIdFromFolderXml_ReturnsNull_WhenNoIdAttribute() {
        byte[] content = "<archimate:Folder name=\"Test\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(GraficoModelLoader.extractIdFromFolderXml(content));
    }

    @Test
    public void extractIdFromFolderXml_ReturnsNull_WhenEmptyContent() {
        byte[] content = "".getBytes(StandardCharsets.UTF_8);
        assertNull(GraficoModelLoader.extractIdFromFolderXml(content));
    }

    @Test
    public void extractIdFromFolderXml_HandlesMultilineXml() {
        String xml = "<archimate:Folder\n"
                + "    xmlns:archimate=\"http://www.archimatetool.com/archimate\"\n"
                + "    name=\"Business\"\n"
                + "    id=\"id-folder-biz\"/>\n";
        assertEquals("id-folder-biz", GraficoModelLoader.extractIdFromFolderXml(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // ========================================================================
    // extractNameFromFolderXml tests
    // ========================================================================

    @Test
    public void extractNameFromFolderXml_ReturnsName() {
        byte[] content = "<archimate:Folder id=\"id-1\" name=\"Business\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("Business", GraficoModelLoader.extractNameFromFolderXml(content));
    }

    @Test
    public void extractNameFromFolderXml_DecodesXmlEntities() {
        byte[] content = "<archimate:Folder id=\"id-1\" name=\"A &amp; B &lt;C&gt; &quot;D&quot;\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("A & B <C> \"D\"", GraficoModelLoader.extractNameFromFolderXml(content));
    }

    @Test
    public void extractNameFromFolderXml_ReturnsNull_WhenNoNameAttribute() {
        byte[] content = "<archimate:Folder id=\"id-1\"/>".getBytes(StandardCharsets.UTF_8);
        assertNull(GraficoModelLoader.extractNameFromFolderXml(content));
    }

    // ========================================================================
    // escapeXml tests
    // ========================================================================

    @Test
    public void escapeXml_PassesThroughPlainText() {
        assertEquals("hello world", GraficoModelLoader.escapeXml("hello world"));
    }

    @Test
    public void escapeXml_EscapesSpecialCharacters() {
        assertEquals("A &amp; B &lt;C&gt; &quot;D&quot;", GraficoModelLoader.escapeXml("A & B <C> \"D\""));
    }

    @Test
    public void escapeXml_HandlesEmptyString() {
        assertEquals("", GraficoModelLoader.escapeXml(""));
    }

    // ========================================================================
    // findDirsWithMissingFolderXml tests
    // ========================================================================

    @Test
    public void findDirsWithMissingFolderXml_FindsDirWithXmlButNoFolderXml() throws IOException {
        File tempDir = new File(GitHelper.getTempTestsFolder(), "modelDir");
        File subDir = new File(tempDir, "subfolder");
        subDir.mkdirs();

        // Create an element XML file but no folder.xml
        Files.writeString(new File(subDir, "BusinessActor_id-123.xml").toPath(), "<element/>");

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        List<File> result = new ArrayList<>();
        loader.findDirsWithMissingFolderXml(tempDir, result);

        // tempDir itself has a subdir (subfolder) but no folder.xml → detected
        // subfolder has an XML file but no folder.xml → detected
        assertTrue(result.contains(subDir));
    }

    @Test
    public void findDirsWithMissingFolderXml_SkipsDirWithFolderXml() throws IOException {
        File tempDir = new File(GitHelper.getTempTestsFolder(), "modelDir");
        File subDir = new File(tempDir, "subfolder");
        subDir.mkdirs();

        // Create folder.xml and element XML
        Files.writeString(new File(subDir, "folder.xml").toPath(), "<archimate:Folder id=\"id-1\" name=\"Test\"/>");
        Files.writeString(new File(subDir, "BusinessActor_id-123.xml").toPath(), "<element/>");

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        List<File> result = new ArrayList<>();
        loader.findDirsWithMissingFolderXml(subDir, result);

        assertFalse(result.contains(subDir));
    }

    @Test
    public void findDirsWithMissingFolderXml_SkipsEmptyDir() throws IOException {
        File tempDir = new File(GitHelper.getTempTestsFolder(), "modelDir");
        File emptyDir = new File(tempDir, "empty");
        emptyDir.mkdirs();

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        List<File> result = new ArrayList<>();
        loader.findDirsWithMissingFolderXml(emptyDir, result);

        assertFalse(result.contains(emptyDir));
    }

    @Test
    public void findDirsWithMissingFolderXml_FindsDirWithSubdirsButNoFolderXml() throws IOException {
        File tempDir = new File(GitHelper.getTempTestsFolder(), "modelDir");
        File parentDir = new File(tempDir, "parent");
        File childDir = new File(parentDir, "child");
        childDir.mkdirs();

        // child has content, parent only has child subdir - but no folder.xml on parent
        Files.writeString(new File(childDir, "folder.xml").toPath(), "<archimate:Folder id=\"id-1\" name=\"Child\"/>");

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        List<File> result = new ArrayList<>();
        loader.findDirsWithMissingFolderXml(parentDir, result);

        // parentDir has subdirs but no folder.xml → detected
        assertTrue(result.contains(parentDir));
        // childDir has folder.xml → not detected
        assertFalse(result.contains(childDir));
    }

    // ========================================================================
    // collectExistingFolderIds tests
    // ========================================================================

    @Test
    public void collectExistingFolderIds_CollectsAllIds() throws IOException {
        File modelDir = new File(GitHelper.getTempTestsFolder(), "model");
        File bizDir = new File(modelDir, "business");
        File techDir = new File(modelDir, "technology");
        bizDir.mkdirs();
        techDir.mkdirs();

        Files.writeString(new File(modelDir, "folder.xml").toPath(),
                "<archimate:Folder id=\"id-root\" name=\"Root\"/>");
        Files.writeString(new File(bizDir, "folder.xml").toPath(),
                "<archimate:Folder id=\"id-biz\" name=\"Business\"/>");
        Files.writeString(new File(techDir, "folder.xml").toPath(),
                "<archimate:Folder id=\"id-tech\" name=\"Technology\"/>");

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        Set<String> ids = loader.collectExistingFolderIds(modelDir);

        assertEquals(3, ids.size());
        assertTrue(ids.contains("id-root"));
        assertTrue(ids.contains("id-biz"));
        assertTrue(ids.contains("id-tech"));
    }

    @Test
    public void collectExistingFolderIds_ReturnsEmptySet_WhenNoFolderXml() throws IOException {
        File modelDir = new File(GitHelper.getTempTestsFolder(), "model");
        modelDir.mkdirs();

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        Set<String> ids = loader.collectExistingFolderIds(modelDir);

        assertTrue(ids.isEmpty());
    }

    // ========================================================================
    // createMergeFixFolderXml tests
    // ========================================================================

    @Test
    public void createMergeFixFolderXml_CreatesFileWithMergeFixPrefix() throws IOException {
        File dir = new File(GitHelper.getTempTestsFolder(), "testDir");
        dir.mkdirs();

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        loader.createMergeFixFolderXml(dir, "Original Name");

        File folderXml = new File(dir, "folder.xml");
        assertTrue(folderXml.exists());

        String content = Files.readString(folderXml.toPath());
        assertTrue(content.contains("name=\"[MERGE FIX] Original Name\""));
        assertTrue(content.contains("id=\"id-"));
        assertTrue(content.contains("xmlns:archimate=\"http://www.archimatetool.com/archimate\""));
    }

    @Test
    public void createMergeFixFolderXml_EscapesSpecialCharsInName() throws IOException {
        File dir = new File(GitHelper.getTempTestsFolder(), "testDir");
        dir.mkdirs();

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        loader.createMergeFixFolderXml(dir, "A & B <C>");

        File folderXml = new File(dir, "folder.xml");
        String content = Files.readString(folderXml.toPath());
        assertTrue(content.contains("name=\"[MERGE FIX] A &amp; B &lt;C&gt;\""));
    }

    @Test
    public void createMergeFixFolderXml_GeneratesValidId() throws IOException {
        File dir = new File(GitHelper.getTempTestsFolder(), "testDir");
        dir.mkdirs();

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        loader.createMergeFixFolderXml(dir, "Test");

        File folderXml = new File(dir, "folder.xml");
        String content = Files.readString(folderXml.toPath());
        String id = GraficoModelLoader.extractIdFromFolderXml(content.getBytes(StandardCharsets.UTF_8));
        assertNotNull(id);
        assertTrue(id.startsWith("id-"));
        assertTrue(id.length() > 10); // UUID-based, should be long
    }

    // ========================================================================
    // repairConnectionEndpoints tests
    // ========================================================================

    @Test
    public void repairConnectionEndpoints_ReturnsZero_WhenNoConnections() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        assertEquals(0, loader.repairConnectionEndpoints(model));
    }

    @Test
    public void repairConnectionEndpoints_ReturnsZero_WhenConnectionsCorrect() {
        // API-created connections always match, so this verifies the "no repair needed" path
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();

        IArchimateElement elementA = IArchimateFactory.eINSTANCE.createBusinessActor();
        elementA.setName("A");
        IArchimateElement elementB = IArchimateFactory.eINSTANCE.createBusinessRole();
        elementB.setName("B");

        model.getDefaultFolderForObject(elementA).getElements().add(elementA);
        model.getDefaultFolderForObject(elementB).getElements().add(elementB);

        IArchimateRelationship rel = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        rel.setSource(elementA);
        rel.setTarget(elementB);
        model.getDefaultFolderForObject(rel).getElements().add(rel);

        IArchimateDiagramModel diagram = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        diagram.setName("Test");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(diagram);

        IDiagramModelArchimateObject dmoA = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        dmoA.setArchimateConcept(elementA);
        diagram.getChildren().add(dmoA);

        IDiagramModelArchimateObject dmoB = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        dmoB.setArchimateConcept(elementB);
        diagram.getChildren().add(dmoB);

        IDiagramModelArchimateConnection conn = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        conn.connect(dmoA, dmoB); // Correct

        GraficoModelLoader loader = new GraficoModelLoader(createDummyRepository(), true);
        assertEquals(0, loader.repairConnectionEndpoints(model));
    }

    @Test
    public void repairConnectionEndpoints_RepairsMismatchedTarget_FromGraficoFiles() throws IOException, GitAPIException {
        // This test simulates the real-world scenario: after a git merge, the diagram XML
        // has a connection whose target attribute points to the WRONG diagram object, while
        // the archimateRelationship href still correctly references A→B.
        //
        // GRAFICO structure:
        //   model/folder.xml           (model root)
        //   model/business/folder.xml  (business folder)
        //   model/business/BusinessActor_id-a.xml
        //   model/business/BusinessRole_id-b.xml
        //   model/business/BusinessProcess_id-c.xml
        //   model/relations/folder.xml
        //   model/relations/AssociationRelationship_id-rel1.xml  (A → B)
        //   model/diagrams/folder.xml
        //   model/diagrams/ArchimateDiagramModel_id-diag.xml
        //     - dmoA (id-dmo-a) references element A, has connection to id-dmo-c (WRONG!)
        //     - dmoB (id-dmo-b) references element B (correct target)
        //     - dmoC (id-dmo-c) references element C
        //     - connection references rel1 (A→B) but target attr says id-dmo-c instead of id-dmo-b

        File repoFolder = new File(GitHelper.getTempTestsFolder(), "connectionRepairRepo");
        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            File modelDir = new File(repoFolder, "model");

            // Model root
            mkdirAndWrite(modelDir, "folder.xml",
                    "<archimate:ArchimateModel xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Test\" id=\"id-model\" version=\"5.0.0\"/>\n");

            // Business folder with elements A, B, C
            File businessDir = new File(modelDir, "business");
            mkdirAndWrite(businessDir, "folder.xml",
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Business\" id=\"id-bus\" type=\"business\"/>\n");
            Files.writeString(new File(businessDir, "BusinessActor_id-a.xml").toPath(),
                    "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Element A\" id=\"id-a\"/>\n");
            Files.writeString(new File(businessDir, "BusinessRole_id-b.xml").toPath(),
                    "<archimate:BusinessRole xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Element B\" id=\"id-b\"/>\n");
            Files.writeString(new File(businessDir, "BusinessProcess_id-c.xml").toPath(),
                    "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Element C\" id=\"id-c\"/>\n");

            // Relations folder with A→B relationship
            File relationsDir = new File(modelDir, "relations");
            mkdirAndWrite(relationsDir, "folder.xml",
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Relations\" id=\"id-rels\" type=\"relations\"/>\n");
            Files.writeString(new File(relationsDir, "AssociationRelationship_id-rel1.xml").toPath(),
                    "<archimate:AssociationRelationship"
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                    + " xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " id=\"id-rel1\">"
                    + "<source xsi:type=\"archimate:BusinessActor\""
                    + " href=\"BusinessActor_id-a.xml#id-a\"/>"
                    + "<target xsi:type=\"archimate:BusinessRole\""
                    + " href=\"BusinessRole_id-b.xml#id-b\"/>"
                    + "</archimate:AssociationRelationship>\n");

            // Empty folders required by the importer
            for(String folder : new String[]{"application", "technology", "motivation",
                    "implementation_migration", "other", "strategy"}) {
                File dir = new File(modelDir, folder);
                mkdirAndWrite(dir, "folder.xml",
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"" + folder + "\" id=\"id-" + folder + "\" type=\"" + folder + "\"/>\n");
            }

            // Diagrams folder with CORRUPTED diagram:
            // Connection target points to id-dmo-c (element C) instead of id-dmo-b (element B)
            File diagramsDir = new File(modelDir, "diagrams");
            mkdirAndWrite(diagramsDir, "folder.xml",
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Views\" id=\"id-views\" type=\"diagrams\"/>\n");

            String diagramXml = "<archimate:ArchimateDiagramModel"
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                    + " xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Test Diagram\" id=\"id-diag\">\n"
                    // dmoA: source of connection, references element A
                    + "  <children xsi:type=\"archimate:DiagramModelArchimateObject\""
                    + " id=\"id-dmo-a\">\n"
                    + "    <sourceConnections"
                    + " xsi:type=\"archimate:DiagramModelArchimateConnection\""
                    + " id=\"id-conn1\""
                    + " source=\"id-dmo-a\""
                    + " target=\"id-dmo-c\">\n"  // <<< WRONG: should be id-dmo-b
                    + "      <archimateRelationship xsi:type=\"archimate:AssociationRelationship\""
                    + " href=\"AssociationRelationship_id-rel1.xml#id-rel1\"/>\n"
                    + "    </sourceConnections>\n"
                    + "    <bounds x=\"10\" y=\"10\" width=\"120\" height=\"55\"/>\n"
                    + "    <archimateElement xsi:type=\"archimate:BusinessActor\""
                    + " href=\"BusinessActor_id-a.xml#id-a\"/>\n"
                    + "  </children>\n"
                    // dmoB: correct target, references element B
                    + "  <children xsi:type=\"archimate:DiagramModelArchimateObject\""
                    + " id=\"id-dmo-b\""
                    + " targetConnections=\"\">\n"
                    + "    <bounds x=\"200\" y=\"10\" width=\"120\" height=\"55\"/>\n"
                    + "    <archimateElement xsi:type=\"archimate:BusinessRole\""
                    + " href=\"BusinessRole_id-b.xml#id-b\"/>\n"
                    + "  </children>\n"
                    // dmoC: wrongly targeted by connection, references element C
                    + "  <children xsi:type=\"archimate:DiagramModelArchimateObject\""
                    + " id=\"id-dmo-c\""
                    + " targetConnections=\"id-conn1\">\n"
                    + "    <bounds x=\"400\" y=\"10\" width=\"120\" height=\"55\"/>\n"
                    + "    <archimateElement xsi:type=\"archimate:BusinessProcess\""
                    + " href=\"BusinessProcess_id-c.xml#id-c\"/>\n"
                    + "  </children>\n"
                    + "</archimate:ArchimateDiagramModel>\n";

            Files.writeString(new File(diagramsDir, "ArchimateDiagramModel_id-diag.xml").toPath(), diagramXml);

            // Commit so git repo is valid
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: model with corrupted connection target").call();
            }

            // === Import the GRAFICO model ===
            GraficoModelImporter importer = new GraficoModelImporter(repoFolder);
            IArchimateModel model = importer.importAsModel();
            assertNotNull(model, "Model should import successfully");

            // Verify pre-conditions: connection target is WRONG (points to element C, not B)
            IDiagramModelArchimateConnection conn = null;
            for(java.util.Iterator<org.eclipse.emf.ecore.EObject> iter = model.eAllContents(); iter.hasNext();) {
                org.eclipse.emf.ecore.EObject obj = iter.next();
                if(obj instanceof IDiagramModelArchimateConnection c && "id-conn1".equals(c.getId())) {
                    conn = c;
                    break;
                }
            }
            assertNotNull(conn, "Connection id-conn1 should exist in imported model");

            IArchimateRelationship rel = conn.getArchimateRelationship();
            assertNotNull(rel, "Connection should reference a relationship");
            assertEquals("id-a", rel.getSource().getId(), "Relationship source should be element A");
            assertEquals("id-b", rel.getTarget().getId(), "Relationship target should be element B");

            // The connection's visual target points to element C (the GRAFICO corruption)
            IConnectable visualTarget = conn.getTarget();
            assertTrue(visualTarget instanceof IDiagramModelArchimateObject,
                    "Target should be a diagram object");
            assertEquals("id-c",
                    ((IDiagramModelArchimateObject) visualTarget).getArchimateConcept().getId(),
                    "Before repair: connection target should point to element C (the corruption)");

            // ModelChecker should detect the mismatch BEFORE repair
            ModelChecker checkerBefore = new ModelChecker(model);
            assertFalse(checkerBefore.checkAll(), "ModelChecker should detect connection endpoint mismatch");
            assertTrue(checkerBefore.getErrorMessages().stream()
                    .anyMatch(msg -> msg.contains("wrong") && msg.contains("end component")),
                    "Error should mention wrong end component, got: " + checkerBefore.getErrorMessages());

            // === Run repair ===
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairConnectionEndpoints(model);

            // === Verify repair ===
            assertEquals(1, repaired, "Should have repaired exactly 1 connection");

            // Connection target should now point to element B (matching the relationship)
            IConnectable repairedTarget = conn.getTarget();
            assertTrue(repairedTarget instanceof IDiagramModelArchimateObject);
            assertEquals("id-b",
                    ((IDiagramModelArchimateObject) repairedTarget).getArchimateConcept().getId(),
                    "After repair: connection target should point to element B");

            // Connection source should still be element A
            IConnectable repairedSource = conn.getSource();
            assertTrue(repairedSource instanceof IDiagramModelArchimateObject);
            assertEquals("id-a",
                    ((IDiagramModelArchimateObject) repairedSource).getArchimateConcept().getId(),
                    "After repair: connection source should still be element A");

            // ModelChecker should pass AFTER repair
            ModelChecker checkerAfter = new ModelChecker(model);
            assertTrue(checkerAfter.checkAll(), "ModelChecker should pass after repair: "
                    + String.join("; ", checkerAfter.getErrorMessages()));
        }
    }

    @Test
    public void repairMissingFolderXml_ImportProducesValidModel() throws IOException, GitAPIException {
        // End-to-end test: create proper GRAFICO files, delete a folder.xml to simulate
        // merge issue, repair, import, and verify ModelChecker passes.
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "folderRepairE2E");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            File modelDir = new File(repoFolder, "model");

            // Model root
            mkdirAndWrite(modelDir, "folder.xml",
                    "<archimate:ArchimateModel xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Test\" id=\"id-model\" version=\"5.0.0\"/>\n");

            // Business folder with one element
            File bizDir = new File(modelDir, "business");
            mkdirAndWrite(bizDir, "folder.xml",
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Business\" id=\"id-bus\" type=\"business\"/>\n");
            Files.writeString(new File(bizDir, "BusinessActor_id-actor1.xml").toPath(),
                    "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Actor One\" id=\"id-actor1\"/>\n");

            // Business subfolder with another element
            File subDir = new File(bizDir, "id-sub1");
            mkdirAndWrite(subDir, "folder.xml",
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Sub Team\" id=\"id-sub1\"/>\n");
            Files.writeString(new File(subDir, "BusinessRole_id-role1.xml").toPath(),
                    "<archimate:BusinessRole xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Role One\" id=\"id-role1\"/>\n");

            // Empty standard folders required by importer
            for(String folder : new String[]{"application", "technology", "motivation",
                    "implementation_migration", "other", "strategy", "relations", "diagrams"}) {
                mkdirAndWrite(new File(modelDir, folder), "folder.xml",
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"" + folder + "\" id=\"id-" + folder + "\" type=\"" + folder + "\"/>\n");
            }

            // Commit with the subfolder intact
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: model with subfolder").call();
            }

            // Simulate merge problem: delete subfolder's folder.xml but keep element
            File subFolderXml = new File(subDir, "folder.xml");
            assertTrue(subFolderXml.delete(), "Should delete subfolder's folder.xml");
            assertTrue(new File(subDir, "BusinessRole_id-role1.xml").exists(),
                    "Element file should still exist");

            // Without repair, import would lose the element silently or fail.
            // Run repair first.
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairMissingFolderXml();
            assertEquals(1, repaired, "Should repair exactly 1 missing folder.xml");
            assertTrue(subFolderXml.exists(), "folder.xml should be restored");

            // Import the repaired model
            GraficoModelImporter importer = new GraficoModelImporter(repoFolder);
            IArchimateModel model = importer.importAsModel();
            assertNotNull(model, "Model should import successfully");

            // Verify both elements were imported
            boolean foundActor = false;
            boolean foundRole = false;
            for(java.util.Iterator<org.eclipse.emf.ecore.EObject> iter = model.eAllContents(); iter.hasNext();) {
                org.eclipse.emf.ecore.EObject obj = iter.next();
                if(obj instanceof IArchimateElement e) {
                    if("id-actor1".equals(e.getId())) foundActor = true;
                    if("id-role1".equals(e.getId())) foundRole = true;
                }
            }
            assertTrue(foundActor, "Actor element should be in imported model");
            assertTrue(foundRole, "Role element from subfolder should be in imported model (not lost)");

            // ModelChecker should pass on the repaired+imported model
            ModelChecker checker = new ModelChecker(model);
            assertTrue(checker.checkAll(), "ModelChecker should pass after folder repair + import: "
                    + String.join("; ", checker.getErrorMessages()));
        }
    }

    /**
     * Helper: create directory and write a file in it.
     */
    private void mkdirAndWrite(File dir, String fileName, String content) throws IOException {
        dir.mkdirs();
        Files.writeString(new File(dir, fileName).toPath(), content);
    }

    // ========================================================================
    // repairMissingFolderXml integration tests (requires git repo)
    // ========================================================================

    @Test
    public void repairMissingFolderXml_RestoresFromHistory() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // Set up GRAFICO model structure
            File modelDir = new File(repoFolder, "model");
            File bizDir = new File(modelDir, "business");
            bizDir.mkdirs();

            String folderXmlContent = "<archimate:Folder\n"
                    + "    xmlns:archimate=\"http://www.archimatetool.com/archimate\"\n"
                    + "    name=\"Business\"\n"
                    + "    id=\"id-biz-folder\"/>\n";

            // Root model folder.xml
            Files.writeString(new File(modelDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Model\" id=\"id-root\"/>\n");

            // Business folder.xml + element
            File bizFolderXml = new File(bizDir, "folder.xml");
            Files.writeString(bizFolderXml.toPath(), folderXmlContent);
            Files.writeString(new File(bizDir, "BusinessActor_id-elem.xml").toPath(), "<element/>");

            // Commit
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial commit").call();
            }

            // Simulate merge problem: delete folder.xml but leave element
            bizFolderXml.delete();
            assertFalse(bizFolderXml.exists());

            // Repair
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairMissingFolderXml();

            assertEquals(1, repaired);
            assertTrue(bizFolderXml.exists());

            // Verify restored content
            String restored = Files.readString(bizFolderXml.toPath());
            assertTrue(restored.contains("id-biz-folder"));
            assertTrue(restored.contains("Business"));
        }
    }

    @Test
    public void repairMissingFolderXml_CreatesMergeFixFolder_WhenNotInHistory() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // Set up GRAFICO model structure with root only
            File modelDir = new File(repoFolder, "model");
            modelDir.mkdirs();
            Files.writeString(new File(modelDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Model\" id=\"id-root\"/>\n");

            // Commit without the business subfolder
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial commit").call();
            }

            // Create orphan directory with element but no folder.xml (never existed in history)
            File newDir = new File(modelDir, "orphan");
            newDir.mkdirs();
            Files.writeString(new File(newDir, "BusinessActor_id-orphan.xml").toPath(), "<element/>");

            // Repair
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairMissingFolderXml();

            assertEquals(1, repaired);

            // Should have created a [MERGE FIX] folder.xml
            File folderXml = new File(newDir, "folder.xml");
            assertTrue(folderXml.exists());
            String content = Files.readString(folderXml.toPath());
            assertTrue(content.contains("[MERGE FIX]"));
        }
    }

    @Test
    public void repairMissingFolderXml_DetectsFolderMove() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // Set up model structure with a subfolder
            File modelDir = new File(repoFolder, "model");
            File bizDir = new File(modelDir, "business");
            File subDir = new File(bizDir, "subfolder");
            subDir.mkdirs();

            Files.writeString(new File(modelDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Model\" id=\"id-root\"/>\n");
            Files.writeString(new File(bizDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Business\" id=\"id-biz\"/>\n");
            Files.writeString(new File(subDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Shared Services\" id=\"id-shared\"/>\n");
            Files.writeString(new File(subDir, "BusinessActor_id-elem.xml").toPath(), "<element/>");

            // Commit: subfolder exists at business/subfolder with id-shared
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial commit").call();
            }

            // Simulate folder move: id-shared now exists at technology/subfolder
            File techDir = new File(modelDir, "technology");
            File movedDir = new File(techDir, "subfolder");
            movedDir.mkdirs();
            Files.writeString(new File(techDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Technology\" id=\"id-tech\"/>\n");
            Files.writeString(new File(movedDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Shared Services\" id=\"id-shared\"/>\n");

            // Remove folder.xml from original location (simulating merge artifact)
            new File(subDir, "folder.xml").delete();
            // Element file still there (orphaned)

            // Repair
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairMissingFolderXml();

            // Move detected — not immediately repaired
            assertEquals(0, repaired, "Simple repairs should be 0 (only moves detected)");
            assertTrue(loader.hasPendingFolderMoves(), "Should have detected folder move");
            
            // Apply with default choice (keep new location)
            int moveRepairs = loader.applyFolderMoveResolutions();
            assertTrue(moveRepairs > 0, "Should have applied move repairs");

            // Should have created a [MERGE FIX] folder (not restored the original id-shared)
            File repairedFolderXml = new File(subDir, "folder.xml");
            assertTrue(repairedFolderXml.exists());
            String content = Files.readString(repairedFolderXml.toPath());
            assertTrue(content.contains("[MERGE FIX]"), "Should contain [MERGE FIX] prefix");
            assertFalse(content.contains("id-shared"), "Should NOT restore old id (would duplicate)");
        }
    }

    /**
     * Realistic two-branch merge scenario:
     * 
     * Initial state: model/business/shared/ has folder.xml (id-shared), E1.xml, E2.xml
     * 
     * Branch A (from initial): adds E3.xml under model/business/shared/
     * Branch B (from initial): moves shared/ to model/technology/shared/ (folder.xml with id-shared),
     *                          deletes E2.xml from the new location
     * 
     * After merging A into B:
     * - model/technology/shared/folder.xml exists with id-shared (from B's move)
     * - model/technology/shared/E1.xml exists (from B's move)
     * - model/technology/shared/E2.xml deleted (by B)
     * - model/business/shared/E3.xml exists (from A's add) but NO folder.xml
     *   (B deleted it as part of the move, A didn't touch it)
     * - model/business/shared/E1.xml gone (B moved it)
     * 
     * Repair should:
     * - Detect that id-shared exists at technology/shared/ → folder move
     * - Create [MERGE FIX] folder at business/shared/ with new ID
     * - E3 is preserved (not lost)
     */
    @Test
    public void repairMissingFolderXml_TwoBranchMerge_FolderMoveAndAdd() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            // Get the default branch name dynamically (may be "master" or "main")
            String defaultBranch = gitRepo.getBranch();

            File modelDir = new File(repoFolder, "model");
            File bizDir = new File(modelDir, "business");
            File sharedDir = new File(bizDir, "shared");
            sharedDir.mkdirs();

            // Root and business folder.xml
            Files.writeString(new File(modelDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Model\" id=\"id-root\"/>\n");
            Files.writeString(new File(bizDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Business\" id=\"id-biz\"/>\n");
            Files.writeString(new File(sharedDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Shared Services\" id=\"id-shared\"/>\n");
            Files.writeString(new File(sharedDir, "BusinessActor_id-e1.xml").toPath(), "<e1/>");
            Files.writeString(new File(sharedDir, "BusinessRole_id-e2.xml").toPath(), "<e2/>");

            // === Initial commit (both branches start from here) ===
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: shared folder with E1, E2").call();

                // === Branch A: add E3 under the OLD location ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                Files.writeString(new File(sharedDir, "BusinessProcess_id-e3.xml").toPath(), "<e3/>");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchA: add E3 to shared").call();

                // === Branch B: move shared/ to technology/, delete E2 ===
                git.checkout().setName(defaultBranch).call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                // Create technology folder and move shared there
                File techDir = new File(modelDir, "technology");
                File movedShared = new File(techDir, "shared");
                movedShared.mkdirs();

                Files.writeString(new File(techDir, "folder.xml").toPath(),
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Technology\" id=\"id-tech\"/>\n");
                // Move: same id-shared at new location
                Files.writeString(new File(movedShared, "folder.xml").toPath(),
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Shared Services\" id=\"id-shared\"/>\n");
                // E1 moved, E2 deleted
                Files.writeString(new File(movedShared, "BusinessActor_id-e1.xml").toPath(), "<e1/>");
                // E2 intentionally NOT copied — deleted by branch B

                // Stage new technology files
                git.add().addFilepattern(".").call();

                // Remove old location files via git rm (stages deletion AND removes from working tree).
                // NOTE: JGit's git.add().addFilepattern(".") does NOT stage deletions!
                // You must use git.rm() or git.add().setUpdate(true) to properly stage file removals.
                git.rm().addFilepattern("model/business/shared/folder.xml").call();
                git.rm().addFilepattern("model/business/shared/BusinessActor_id-e1.xml").call();
                git.rm().addFilepattern("model/business/shared/BusinessRole_id-e2.xml").call();

                git.commit().setMessage("branchB: move shared to technology, delete E2").call();

                // === Merge branchA into branchB ===
                MergeResult mergeResult = git.merge()
                    .include(gitRepo.resolve("branchA"))
                    .setMessage("merge branchA into branchB")
                    .call();

                assertEquals(MergeResult.MergeStatus.MERGED, mergeResult.getMergeStatus(),
                        "Merge should succeed without conflicts");
            }

            // === Validate actual post-merge state (no manual fixup — real git result) ===
            File e3File = new File(sharedDir, "BusinessProcess_id-e3.xml");
            File oldFolderXml = new File(sharedDir, "folder.xml");
            File newFolderXml = new File(modelDir, "technology/shared/folder.xml");

            // E3 exists at old location (added by branch A, auto-merged in)
            assertTrue(e3File.exists(), "E3 should exist at old location (from branch A)");
            // No folder.xml at old location (removed by branch B via git rm, not touched by branch A)
            assertFalse(oldFolderXml.exists(), "folder.xml should be missing at old location");
            // folder.xml exists at new location with id-shared
            assertTrue(newFolderXml.exists(), "folder.xml should exist at new location");
            assertTrue(Files.readString(newFolderXml.toPath()).contains("id-shared"));
            // E1 at new location (from move)
            assertTrue(new File(modelDir, "technology/shared/BusinessActor_id-e1.xml").exists(),
                    "E1 should exist at new location (moved by branch B)");
            // E2 gone (deleted by branch B)
            assertFalse(new File(modelDir, "technology/shared/BusinessRole_id-e2.xml").exists(),
                    "E2 should be deleted (by branch B)");
            // E1 gone from old location (removed by branch B via git rm)
            assertFalse(new File(sharedDir, "BusinessActor_id-e1.xml").exists(),
                    "E1 should be gone from old location (moved by branch B)");

            // === Run repair ===
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairMissingFolderXml();
            
            // Should have detected folder moves
            assertTrue(loader.hasPendingFolderMoves(), "Should have detected folder move");
            
            // Apply with default choice (keep new location)
            int moveRepairs = loader.applyFolderMoveResolutions();

            // === Validate repair results ===
            assertTrue(repaired + moveRepairs > 0, "Should have repaired at least one folder");

            // Old location: [MERGE FIX] folder.xml with NEW id (not id-shared)
            assertTrue(oldFolderXml.exists(), "folder.xml should be created at old location");
            String repairedContent = Files.readString(oldFolderXml.toPath());
            assertTrue(repairedContent.contains("[MERGE FIX]"),
                    "Should have [MERGE FIX] prefix (move detected)");
            assertFalse(repairedContent.contains("id-shared"),
                    "Should NOT reuse id-shared (would create duplicate)");

            // E3 preserved at old location (the whole point!)
            assertTrue(e3File.exists(), "E3 must still exist (branch A's addition preserved)");

            // New location unchanged
            assertTrue(Files.readString(newFolderXml.toPath()).contains("id-shared"),
                    "New location folder.xml should still have id-shared");
            assertTrue(new File(modelDir, "technology/shared/BusinessActor_id-e1.xml").exists(),
                    "E1 should still exist at new location");
            assertFalse(new File(modelDir, "technology/shared/BusinessRole_id-e2.xml").exists(),
                    "E2 should still be deleted");
        }
    }

    /**
     * Expanded two-branch merge scenario with recursive folder move, duplicate elements,
     * and element renames across branches.
     * 
     * Initial state:
     *   model/business/shared/
     *     folder.xml (id-shared, name="Shared Services")
     *     BusinessActor_id-e1.xml (name="Actor Original")
     *     BusinessRole_id-e2.xml  (name="Role Original")
     *     sub/
     *       folder.xml (id-sub, name="Sub Folder")
     *       BusinessProcess_id-e4.xml (name="Process Original")
     * 
     * Branch A (from initial):
     *   - Renames E1: name="Actor Renamed by A" (same file, different content)
     *   - Adds E3: BusinessProcess_id-e3.xml (name="New Process by A")
     *   - Renames E4: name="Process Renamed by A" (same file, different content)
     * 
     * Branch B (from initial):
     *   - Moves shared/ → model/technology/shared/ (keeping id-shared and id-sub)
     *   - All elements (E1, E2, E4) move with it
     * 
     * After merge (A into B), post-conflict resolution:
     *   model/technology/shared/          ← from B's move
     *     folder.xml (id-shared)
     *     BusinessActor_id-e1.xml         ← B's version (original name, from move)
     *     BusinessRole_id-e2.xml          ← from move
     *     sub/
     *       folder.xml (id-sub)
     *       BusinessProcess_id-e4.xml     ← B's version (original name, from move)
     *   model/business/shared/            ← elements left from A's modifications
     *     BusinessActor_id-e1.xml         ← A's version (renamed) — DUPLICATE of destination
     *     BusinessProcess_id-e3.xml       ← genuinely new — NOT at destination
     *     sub/
     *       BusinessProcess_id-e4.xml     ← A's version (renamed) — DUPLICATE of destination
     *     (NO folder.xml at either level)
     * 
     * Expected repair:
     *   1. business/shared/: move detected (id-shared)
     *      - E1 duplicate of technology/shared/ → REMOVED
     *      - E3 unique → KEPT
     *      - [MERGE FIX] folder created (has unique elements)
     *   2. business/shared/sub/: move detected (id-sub)
     *      - E4 duplicate of technology/shared/sub/ → REMOVED
     *      - No unique elements remain → NO [MERGE FIX] folder, directory skipped
     */
    @Test
    public void repairMissingFolderXml_TwoBranchMerge_MovedFolderWithDuplicatesAndRenames() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            File modelDir = new File(repoFolder, "model");
            File bizDir = new File(modelDir, "business");
            File sharedDir = new File(bizDir, "shared");
            File subDir = new File(sharedDir, "sub");
            subDir.mkdirs();

            // === Initial state ===
            Files.writeString(new File(modelDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Model\" id=\"id-root\"/>\n");
            Files.writeString(new File(bizDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Business\" id=\"id-biz\"/>\n");
            Files.writeString(new File(sharedDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Shared Services\" id=\"id-shared\"/>\n");
            Files.writeString(new File(subDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Sub Folder\" id=\"id-sub\"/>\n");
            Files.writeString(new File(sharedDir, "BusinessActor_id-e1.xml").toPath(),
                    "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Actor Original\" id=\"id-e1\"/>\n");
            Files.writeString(new File(sharedDir, "BusinessRole_id-e2.xml").toPath(),
                    "<archimate:BusinessRole xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Role Original\" id=\"id-e2\"/>\n");
            Files.writeString(new File(subDir, "BusinessProcess_id-e4.xml").toPath(),
                    "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Process Original\" id=\"id-e4\"/>\n");

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial: shared folder with E1, E2, sub/E4").call();

                // === Branch A: rename E1, add E3, rename E4 ===
                git.branchCreate().setName("branchA").call();
                git.checkout().setName("branchA").call();

                Files.writeString(new File(sharedDir, "BusinessActor_id-e1.xml").toPath(),
                        "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Actor Renamed by A\" id=\"id-e1\"/>\n");
                Files.writeString(new File(sharedDir, "BusinessProcess_id-e3.xml").toPath(),
                        "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"New Process by A\" id=\"id-e3\"/>\n");
                Files.writeString(new File(subDir, "BusinessProcess_id-e4.xml").toPath(),
                        "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Process Renamed by A\" id=\"id-e4\"/>\n");

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchA: rename E1, add E3, rename E4").call();

                // === Branch B: move shared/ to technology/ ===
                git.checkout().setName("master").call();
                git.branchCreate().setName("branchB").call();
                git.checkout().setName("branchB").call();

                File techDir = new File(modelDir, "technology");
                File movedShared = new File(techDir, "shared");
                File movedSub = new File(movedShared, "sub");
                movedSub.mkdirs();

                Files.writeString(new File(techDir, "folder.xml").toPath(),
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Technology\" id=\"id-tech\"/>\n");
                Files.writeString(new File(movedShared, "folder.xml").toPath(),
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Shared Services\" id=\"id-shared\"/>\n");
                Files.writeString(new File(movedSub, "folder.xml").toPath(),
                        "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Sub Folder\" id=\"id-sub\"/>\n");
                // All elements moved with their original content
                Files.writeString(new File(movedShared, "BusinessActor_id-e1.xml").toPath(),
                        "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Actor Original\" id=\"id-e1\"/>\n");
                Files.writeString(new File(movedShared, "BusinessRole_id-e2.xml").toPath(),
                        "<archimate:BusinessRole xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Role Original\" id=\"id-e2\"/>\n");
                Files.writeString(new File(movedSub, "BusinessProcess_id-e4.xml").toPath(),
                        "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                        + " name=\"Process Original\" id=\"id-e4\"/>\n");

                // Remove old location
                new File(subDir, "BusinessProcess_id-e4.xml").delete();
                new File(subDir, "folder.xml").delete();
                subDir.delete();
                new File(sharedDir, "folder.xml").delete();
                new File(sharedDir, "BusinessActor_id-e1.xml").delete();
                new File(sharedDir, "BusinessRole_id-e2.xml").delete();
                sharedDir.delete();

                git.add().addFilepattern(".").call();
                git.commit().setMessage("branchB: move shared+sub to technology").call();

                // === Merge branchA into branchB ===
                git.merge()
                    .include(gitRepo.resolve("branchA"))
                    .setMessage("merge branchA into branchB")
                    .call();
            }

            // === Simulate post-merge state ===
            // Git merge may or may not produce the exact state we need.
            // Ensure the scenario is exactly as described: duplicate elements at old location,
            // unique element E3 at old location, no folder.xml at old location.
            sharedDir.mkdirs();
            subDir.mkdirs();

            // Destination (technology/) should already be correct from branchB
            File techShared = new File(modelDir, "technology/shared");
            File techSub = new File(techShared, "sub");

            // Ensure destination has original-named elements (from B's move)
            Files.writeString(new File(techShared, "BusinessActor_id-e1.xml").toPath(),
                    "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Actor Original\" id=\"id-e1\"/>\n");
            Files.writeString(new File(techShared, "BusinessRole_id-e2.xml").toPath(),
                    "<archimate:BusinessRole xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Role Original\" id=\"id-e2\"/>\n");
            assertTrue(new File(techShared, "folder.xml").exists(), "tech/shared/folder.xml should exist");
            Files.writeString(new File(techSub, "BusinessProcess_id-e4.xml").toPath(),
                    "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Process Original\" id=\"id-e4\"/>\n");
            assertTrue(new File(techSub, "folder.xml").exists(), "tech/shared/sub/folder.xml should exist");

            // Old location: A's renamed E1 (duplicate), A's new E3 (unique), A's renamed E4 (duplicate)
            Files.writeString(new File(sharedDir, "BusinessActor_id-e1.xml").toPath(),
                    "<archimate:BusinessActor xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Actor Renamed by A\" id=\"id-e1\"/>\n");
            Files.writeString(new File(sharedDir, "BusinessProcess_id-e3.xml").toPath(),
                    "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"New Process by A\" id=\"id-e3\"/>\n");
            Files.writeString(new File(subDir, "BusinessProcess_id-e4.xml").toPath(),
                    "<archimate:BusinessProcess xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Process Renamed by A\" id=\"id-e4\"/>\n");

            // Ensure NO folder.xml at old locations
            new File(sharedDir, "folder.xml").delete();
            new File(subDir, "folder.xml").delete();

            // Stage everything so git knows the current state
            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("simulate post-merge state").call();
            }

            // === Validate pre-conditions ===
            assertTrue(new File(sharedDir, "BusinessActor_id-e1.xml").exists(), "E1 at old location (duplicate)");
            assertTrue(new File(sharedDir, "BusinessProcess_id-e3.xml").exists(), "E3 at old location (unique)");
            assertTrue(new File(subDir, "BusinessProcess_id-e4.xml").exists(), "E4 at old sub location (duplicate)");
            assertFalse(new File(sharedDir, "folder.xml").exists(), "No folder.xml at old shared/");
            assertFalse(new File(subDir, "folder.xml").exists(), "No folder.xml at old sub/");
            assertTrue(new File(techShared, "folder.xml").exists(), "folder.xml at destination shared/");
            assertTrue(new File(techSub, "folder.xml").exists(), "folder.xml at destination sub/");

            // === Run repair ===
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            int repaired = loader.repairMissingFolderXml();
            
            // Moves detected — not immediately repaired
            assertEquals(0, repaired, "Simple repairs should be 0 (only moves detected)");
            assertTrue(loader.hasPendingFolderMoves(), "Should have detected folder moves");
            assertEquals(2, loader.getFolderMoves().size(), "Should detect 2 moves (shared/ and sub/)");
            
            // Apply with default choice (keep new location)
            int moveRepairs = loader.applyFolderMoveResolutions();

            // === Validate results ===

            // 1. business/shared/: [MERGE FIX] folder created (has unique E3)
            File oldFolderXml = new File(sharedDir, "folder.xml");
            assertTrue(oldFolderXml.exists(),
                    "folder.xml should be created at old location (has unique element E3)");
            String repairedContent = Files.readString(oldFolderXml.toPath());
            assertTrue(repairedContent.contains("[MERGE FIX]"),
                    "Should have [MERGE FIX] prefix (move detected)");
            assertFalse(repairedContent.contains("id-shared"),
                    "Should NOT reuse id-shared (would create duplicate)");

            // 2. E1 duplicate removed from old location
            assertFalse(new File(sharedDir, "BusinessActor_id-e1.xml").exists(),
                    "E1 should be REMOVED from old location (duplicate of destination)");

            // 3. E3 unique element preserved at old location
            assertTrue(new File(sharedDir, "BusinessProcess_id-e3.xml").exists(),
                    "E3 must be preserved at old location (unique, not at destination)");

            // 4. business/shared/sub/: NO [MERGE FIX] folder (all elements were duplicates)
            File oldSubFolderXml = new File(subDir, "folder.xml");
            assertFalse(oldSubFolderXml.exists(),
                    "sub/folder.xml should NOT be created (all elements were duplicates, no unique remains)");

            // 5. E4 duplicate removed from old sub/ location
            assertFalse(new File(subDir, "BusinessProcess_id-e4.xml").exists(),
                    "E4 should be REMOVED from old sub/ location (duplicate of destination)");

            // 6. Destination (technology/) unchanged
            assertTrue(Files.readString(new File(techShared, "folder.xml").toPath()).contains("id-shared"),
                    "Destination folder.xml should still have id-shared");
            assertTrue(new File(techShared, "BusinessActor_id-e1.xml").exists(),
                    "E1 should still exist at destination");
            assertTrue(new File(techShared, "BusinessRole_id-e2.xml").exists(),
                    "E2 should still exist at destination");
            assertTrue(new File(techSub, "BusinessProcess_id-e4.xml").exists(),
                    "E4 should still exist at destination sub/");

            // 7. Sub directory should be cleaned up (empty after removing E4)
            // Or at minimum: no folder.xml and no elements ← sub directory itself may still exist
            File[] subFiles = subDir.listFiles();
            assertTrue(subFiles == null || subFiles.length == 0,
                    "Old sub/ directory should be empty after deduplication");

            // 8. Verify move repair count: only business/shared/ gets a [MERGE FIX] folder
            //    business/shared/sub/ is skipped (no unique elements)
            assertEquals(1, moveRepairs,
                    "Only one folder should be repaired (shared/ with E3); sub/ should be skipped");

            // 9. Verify repair details mention the deduplication
            String details = loader.getRepairDetailsAsString();
            assertNotNull(details, "Should have repair details");
            assertTrue(details.contains("MERGE FIX"),
                    "Details should mention [MERGE FIX] folder creation");
            assertTrue(details.contains("Skipped") || details.contains("duplicate"),
                    "Details should mention skipped/duplicate subfolder");
        }
    }

    /**
     * Test "keep old location" choice for folder move resolution.
     * 
     * Scenario: Same as TwoBranchMerge_FolderMoveAndAdd but user chooses to keep old location.
     * 
     * Expected:
     * - Old location gets restored folder.xml with original id-shared
     * - New location gets [MERGE FIX] folder.xml with new ID
     * - Duplicate elements removed from old location
     * - E3 (unique) preserved at old location
     */
    @Test
    public void applyFolderMoveResolutions_KeepOldLocation() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            File modelDir = new File(repoFolder, "model");
            File bizDir = new File(modelDir, "business");
            File sharedDir = new File(bizDir, "shared");
            sharedDir.mkdirs();

            Files.writeString(new File(modelDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Model\" id=\"id-root\"/>\n");
            Files.writeString(new File(bizDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Business\" id=\"id-biz\"/>\n");
            Files.writeString(new File(sharedDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Shared Services\" id=\"id-shared\"/>\n");
            Files.writeString(new File(sharedDir, "BusinessActor_id-e1.xml").toPath(), "<e1/>");

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").call();
            }

            // Set up post-merge state: folder moved to technology/shared/
            File techDir = new File(modelDir, "technology");
            File movedShared = new File(techDir, "shared");
            movedShared.mkdirs();
            Files.writeString(new File(techDir, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Technology\" id=\"id-tech\"/>\n");
            Files.writeString(new File(movedShared, "folder.xml").toPath(),
                    "<archimate:Folder xmlns:archimate=\"http://www.archimatetool.com/archimate\""
                    + " name=\"Shared Services\" id=\"id-shared\"/>\n");
            Files.writeString(new File(movedShared, "BusinessActor_id-e1.xml").toPath(), "<e1/>");

            // Old location: E1 (duplicate) + E3 (unique), no folder.xml
            new File(sharedDir, "folder.xml").delete();
            Files.writeString(new File(sharedDir, "BusinessProcess_id-e3.xml").toPath(), "<e3/>");

            try(Git git = new Git(gitRepo)) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("setup post-merge state").call();
            }

            // === Detect moves ===
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            loader.repairMissingFolderXml();

            assertTrue(loader.hasPendingFolderMoves(), "Should detect folder move");
            assertEquals(1, loader.getFolderMoves().size());

            // === User chooses: keep OLD location ===
            loader.getFolderMoves().get(0).setUserChoice(FolderMoveInfo.KEEP_OLD_LOCATION);

            int repaired = loader.applyFolderMoveResolutions();
            assertTrue(repaired > 0, "Should have repaired folders");

            // === Validate ===

            // Old location: restored folder.xml with ORIGINAL id-shared
            File oldFolderXml = new File(sharedDir, "folder.xml");
            assertTrue(oldFolderXml.exists(), "Old location should have restored folder.xml");
            String oldContent = Files.readString(oldFolderXml.toPath());
            assertTrue(oldContent.contains("id-shared"),
                    "Old location should keep original id-shared");
            assertFalse(oldContent.contains("[MERGE FIX]"),
                    "Old location should NOT be [MERGE FIX]");

            // E1 duplicate removed from old location (exists at new location)
            assertFalse(new File(sharedDir, "BusinessActor_id-e1.xml").exists(),
                    "E1 should be removed from old location (duplicate)");

            // E3 unique preserved at old location
            assertTrue(new File(sharedDir, "BusinessProcess_id-e3.xml").exists(),
                    "E3 should be preserved at old location (unique)");

            // New location: [MERGE FIX] folder.xml with NEW id (not id-shared)
            File newFolderXml = new File(movedShared, "folder.xml");
            assertTrue(newFolderXml.exists(), "New location should have folder.xml");
            String newContent = Files.readString(newFolderXml.toPath());
            assertTrue(newContent.contains("[MERGE FIX]"),
                    "New location should be [MERGE FIX]");
            assertFalse(newContent.contains("id-shared"),
                    "New location should NOT have original id-shared");

            // E1 remains at new location (not a duplicate of old anymore since old has it removed)
            // Actually after removeDuplicateElements(destDir, oldDir):
            // oldDir has E3 only, destDir has E1 — E1 doesn't exist at oldDir so it stays at destDir
            assertTrue(new File(movedShared, "BusinessActor_id-e1.xml").exists(),
                    "E1 should remain at new location (unique there after old's E1 was removed)");
        }
    }

    @Test
    public void repairMissingFolderXml_ReturnsZero_WhenNoModelDirectory() throws IOException, GitAPIException {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");

        try(Repository gitRepo = GitHelper.createNewRepository(repoFolder)) {
            try(Git git = new Git(gitRepo)) {
                Files.writeString(new File(repoFolder, "dummy.txt").toPath(), "hello");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial commit").call();
            }

            // No model/ directory exists
            IArchiRepository repo = new ArchiRepository(repoFolder);
            GraficoModelLoader loader = new GraficoModelLoader(repo, true);
            assertEquals(0, loader.repairMissingFolderXml());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Create a dummy ArchiRepository for tests that don't need a real git repo.
     */
    private IArchiRepository createDummyRepository() {
        File dummyFolder = new File(GitHelper.getTempTestsFolder(), "dummyRepo");
        dummyFolder.mkdirs();
        return new ArchiRepository(dummyFolder);
    }

}
