/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.archicontribs.modelrepository.grafico.GraficoModelExporter;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

/**
 * Helper for creating GRAFICO test fixtures using the real exporter.
 *
 * <p>Instead of writing raw XML strings that may diverge from the actual GRAFICO
 * format, this helper builds an in-memory ArchiMate model with controlled IDs and
 * names, then exports it with {@link GraficoModelExporter}. This ensures test
 * fixtures are always in the format the importer expects.</p>
 *
 * <h3>Usage pattern</h3>
 * <pre>
 * var helper = new GraficoTestHelper();
 * IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
 * IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
 * helper.export(repoFolder);
 *
 * // After export, get file paths for assertions/modifications:
 * File qFile = helper.elementFile(repoFolder, folderX, q); // model/business/id-folderX/BusinessActor_id-q.xml
 * </pre>
 *
 * <h3>Branch modifications</h3>
 * <p>After exporting the initial state and committing, branch modifications should
 * use file-level operations (rename content via string replace, delete files, copy
 * files to new folders). This matches git's view of the world and produces the same
 * conflicts as real-world usage.</p>
 */
@SuppressWarnings("nls")
class GraficoTestHelper {

    private final IArchimateModel model;

    GraficoTestHelper() {
        model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        model.setName("Test");
        model.setId("id-model");
    }

    /** @return the underlying model (e.g. for setting model-level properties) */
    IArchimateModel model() {
        return model;
    }

    /** @return the standard Business folder */
    IFolder businessFolder() {
        return model.getFolder(FolderType.BUSINESS);
    }

    /** @return the standard Application folder */
    IFolder applicationFolder() {
        return model.getFolder(FolderType.APPLICATION);
    }

    // ------------------------------------------------------------------
    // Folder creation
    // ------------------------------------------------------------------

    /**
     * Add a user subfolder under the given parent with a controlled ID.
     */
    IFolder addFolder(IFolder parent, String name, String id) {
        IFolder folder = IArchimateFactory.eINSTANCE.createFolder();
        folder.setName(name);
        folder.setId(id);
        parent.getFolders().add(folder);
        return folder;
    }

    // ------------------------------------------------------------------
    // Element creation
    // ------------------------------------------------------------------

    /**
     * Add a BusinessActor element to a folder with a controlled ID.
     */
    IArchimateElement addBusinessActor(IFolder folder, String name, String id) {
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessActor();
        element.setName(name);
        element.setId(id);
        folder.getElements().add(element);
        return element;
    }

    /**
     * Add a BusinessRole element to a folder with a controlled ID.
     */
    IArchimateElement addBusinessRole(IFolder folder, String name, String id) {
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessRole();
        element.setName(name);
        element.setId(id);
        folder.getElements().add(element);
        return element;
    }

    /**
     * Add an ApplicationComponent element to a folder with a controlled ID.
     */
    IArchimateElement addApplicationComponent(IFolder folder, String name, String id) {
        IArchimateElement element = IArchimateFactory.eINSTANCE.createApplicationComponent();
        element.setName(name);
        element.setId(id);
        folder.getElements().add(element);
        return element;
    }

    /**
     * Add a BusinessProcess element to a folder with a controlled ID.
     */
    IArchimateElement addBusinessProcess(IFolder folder, String name, String id) {
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessProcess();
        element.setName(name);
        element.setId(id);
        folder.getElements().add(element);
        return element;
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Export the model to the given repo folder as GRAFICO files.
     * Creates model/, model/business/, model/application/ etc.
     */
    void export(File repoFolder) throws IOException {
        new GraficoModelExporter(model, repoFolder).exportModel();
    }

    // ------------------------------------------------------------------
    // Path helpers (for locating files after export)
    // ------------------------------------------------------------------

    /**
     * Get the directory for a top-level folder type (e.g. model/business).
     */
    static File topFolderDir(File repoFolder, FolderType type) {
        return new File(repoFolder, "model/" + type.toString());
    }

    /**
     * Get the directory for a user subfolder (e.g. model/business/id-folderX).
     * User folders use their ID as the directory name.
     */
    static File folderDir(File repoFolder, FolderType parentType, IFolder folder) {
        return new File(topFolderDir(repoFolder, parentType), folder.getId());
    }

    /**
     * Get the directory for a user subfolder nested under another user subfolder.
     */
    static File folderDir(File repoFolder, FolderType topType, IFolder parent, IFolder child) {
        return new File(folderDir(repoFolder, topType, parent), child.getId());
    }

    /**
     * Get the file for an element within a folder directory.
     * GRAFICO file name pattern: {SimpleClassName}_{id}.xml
     */
    static File elementFile(File folderDir, IArchimateElement element) {
        String className = element.getClass().getSimpleName();
        return new File(folderDir, className + "_" + element.getId() + ".xml");
    }

    /**
     * Get the GRAFICO-relative path for an element (e.g. model/business/id-folderX/BusinessActor_id-q.xml).
     */
    static String elementPath(FolderType topType, IFolder folder, IArchimateElement element) {
        String className = element.getClass().getSimpleName();
        return "model/" + topType + "/" + folder.getId() + "/" + className + "_" + element.getId() + ".xml";
    }

    // ------------------------------------------------------------------
    // File modification helpers (for branch changes after initial export)
    // ------------------------------------------------------------------

    /**
     * Rename an element in its exported XML file (changes the name attribute).
     */
    static void renameElement(File elementFile, String oldName, String newName) throws IOException {
        String content = Files.readString(elementFile.toPath());
        Files.writeString(elementFile.toPath(),
                content.replace("name=\"" + oldName + "\"", "name=\"" + newName + "\""));
    }

    /**
     * Set documentation on an element in its exported XML file.
     */
    static void setDocumentation(File elementFile, String documentation) throws IOException {
        String content = Files.readString(elementFile.toPath());
        // Insert documentation attribute before the closing />
        Files.writeString(elementFile.toPath(),
                content.replace("/>", " documentation=\"" + documentation + "\"/>"));
    }

    /**
     * Move an element file from one folder directory to another.
     */
    static void moveElementFile(File sourceDir, File targetDir, IArchimateElement element) throws IOException {
        File src = elementFile(sourceDir, element);
        File dst = elementFile(targetDir, element);
        targetDir.mkdirs();
        Files.copy(src.toPath(), dst.toPath());
        src.delete();
    }

    /**
     * Move a folder directory (all contents) to a new parent.
     */
    static void moveFolderDir(File sourceDir, File targetDir) throws IOException {
        targetDir.mkdirs();
        File[] files = sourceDir.listFiles();
        if(files != null) {
            for(File f : files) {
                if(f.isFile()) {
                    Files.copy(f.toPath(), new File(targetDir, f.getName()).toPath());
                    f.delete();
                } else if(f.isDirectory()) {
                    File subTarget = new File(targetDir, f.getName());
                    moveFolderDir(f, subTarget);
                    f.delete();
                }
            }
        }
        sourceDir.delete();
    }

    /**
     * Delete an entire folder directory and all its contents.
     */
    static void deleteFolder(File dir) throws IOException {
        File[] files = dir.listFiles();
        if(files != null) {
            for(File f : files) {
                if(f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    // ------------------------------------------------------------------
    // Branch file-creation helpers
    // These write individual GRAFICO files for branch modifications where
    // new elements/folders are added that didn't exist in the initial model.
    // The format matches what GraficoModelExporter produces.
    // ------------------------------------------------------------------

    private static final String NS = "xmlns:archimate=\"http://www.archimatetool.com/archimate\"";

    /**
     * Write a user folder.xml file (for creating new folder structures on branches).
     */
    static void writeFolderXml(File dir, String name, String id) throws IOException {
        dir.mkdirs();
        Files.writeString(new File(dir, "folder.xml").toPath(),
                "<archimate:Folder " + NS + " name=\"" + name + "\" id=\"" + id + "\"/>\n");
    }

    /**
     * Write a BusinessActor element file to a folder directory.
     */
    static File writeBusinessActor(File dir, String name, String id) throws IOException {
        dir.mkdirs();
        File file = new File(dir, "BusinessActor_" + id + ".xml");
        Files.writeString(file.toPath(),
                "<archimate:BusinessActor " + NS + " name=\"" + name + "\" id=\"" + id + "\"/>\n");
        return file;
    }

    /**
     * Write a BusinessRole element file to a folder directory.
     */
    static File writeBusinessRole(File dir, String name, String id) throws IOException {
        dir.mkdirs();
        File file = new File(dir, "BusinessRole_" + id + ".xml");
        Files.writeString(file.toPath(),
                "<archimate:BusinessRole " + NS + " name=\"" + name + "\" id=\"" + id + "\"/>\n");
        return file;
    }

    /**
     * Write an ApplicationComponent element file to a folder directory.
     */
    static File writeApplicationComponent(File dir, String name, String id) throws IOException {
        dir.mkdirs();
        File file = new File(dir, "ApplicationComponent_" + id + ".xml");
        Files.writeString(file.toPath(),
                "<archimate:ApplicationComponent " + NS + " name=\"" + name + "\" id=\"" + id + "\"/>\n");
        return file;
    }

    /**
     * Write a BusinessProcess element file to a folder directory.
     */
    static File writeBusinessProcess(File dir, String name, String id) throws IOException {
        dir.mkdirs();
        File file = new File(dir, "BusinessProcess_" + id + ".xml");
        Files.writeString(file.toPath(),
                "<archimate:BusinessProcess " + NS + " name=\"" + name + "\" id=\"" + id + "\"/>\n");
        return file;
    }
}
