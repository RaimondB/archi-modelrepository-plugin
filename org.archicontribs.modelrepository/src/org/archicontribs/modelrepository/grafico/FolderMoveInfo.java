/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.util.List;

/**
 * Information about a folder move detected during merge repair.
 * After a git merge, the same folder ID may exist at a new location (moved by one branch)
 * while the old location still has elements (added/modified by the other branch).
 */
public class FolderMoveInfo {

    /** Keep the folder at the new (moved) location — current default behavior */
    public static final int KEEP_NEW_LOCATION = 0;

    /** Keep the folder at the old (original) location */
    public static final int KEEP_OLD_LOCATION = 1;

    private final String folderName;
    private final String folderId;
    private final File oldDir;
    private final File newDir;
    private final String oldRelativePath;
    private final String newRelativePath;
    private final List<String> duplicateElementFiles;
    private final List<String> uniqueElementFiles;
    private final byte[] historicalContent;

    private int userChoice = KEEP_NEW_LOCATION;

    public FolderMoveInfo(String folderName, String folderId,
                          File oldDir, File newDir,
                          String oldRelativePath, String newRelativePath,
                          List<String> duplicateElementFiles,
                          List<String> uniqueElementFiles,
                          byte[] historicalContent) {
        this.folderName = folderName;
        this.folderId = folderId;
        this.oldDir = oldDir;
        this.newDir = newDir;
        this.oldRelativePath = oldRelativePath;
        this.newRelativePath = newRelativePath;
        this.duplicateElementFiles = duplicateElementFiles;
        this.uniqueElementFiles = uniqueElementFiles;
        this.historicalContent = historicalContent;
    }

    public String getFolderName() {
        return folderName;
    }

    public String getFolderId() {
        return folderId;
    }

    public File getOldDir() {
        return oldDir;
    }

    public File getNewDir() {
        return newDir;
    }

    public String getOldRelativePath() {
        return oldRelativePath;
    }

    public String getNewRelativePath() {
        return newRelativePath;
    }

    public List<String> getDuplicateElementFiles() {
        return duplicateElementFiles;
    }

    public List<String> getUniqueElementFiles() {
        return uniqueElementFiles;
    }

    public byte[] getHistoricalContent() {
        return historicalContent;
    }

    public int getUserChoice() {
        return userChoice;
    }

    public void setUserChoice(int choice) {
        this.userChoice = choice;
    }

    /**
     * @return true if there are any unique elements that exist only at the old location
     */
    public boolean hasUniqueElements() {
        return uniqueElementFiles != null && !uniqueElementFiles.isEmpty();
    }

    /**
     * @return true if there are duplicate elements at both locations
     */
    public boolean hasDuplicates() {
        return duplicateElementFiles != null && !duplicateElementFiles.isEmpty();
    }
}
