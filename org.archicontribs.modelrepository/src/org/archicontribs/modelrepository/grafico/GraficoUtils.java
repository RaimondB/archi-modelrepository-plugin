/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateModel;

/**
 * Grafico Utils
 * 
 * @author Phillip Beauvoir
 */
public class GraficoUtils {
    
    private static List<String> sshSchemeNames = Arrays.asList(new String[] {"ssh", "ssh+git", "git+ssh"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * Adapted from org.eclipse.jgit.transport.TransportGitSsh
     * @param url
     * @return
     */
    public static boolean isSSH(String url) {
        if(!StringUtils.isSet(url)) {
            return false;
        }
        
        URIish uri = null;
        
        try {
            uri = new URIish(url);
        }
        catch(URISyntaxException ex) {
            ex.printStackTrace();
            return false;
        }
        
        if(uri.getScheme() == null) {
            return StringUtils.isSet(uri.getHost())
                    && StringUtils.isSet(uri.getPath());
        }
        
        return sshSchemeNames.contains(uri.getScheme()) 
                && StringUtils.isSet(uri.getHost())
                && StringUtils.isSet(uri.getPath());
    }
    
    public static boolean isHTTP(String url) {
        return !isSSH(url);
    }

    /**
     * Get a local git folder name based on the repo's URL
     * @param repoURL
     * @return
     */
    public static String getLocalGitFolderName(String repoURL) {
        repoURL = repoURL.trim();
        
        int index = repoURL.lastIndexOf("/"); //$NON-NLS-1$
        if(index > 0 && index < repoURL.length() - 2) {
            repoURL = repoURL.substring(index + 1).toLowerCase();
        }
        
        index = repoURL.lastIndexOf(".git"); //$NON-NLS-1$
        if(index > 0 && index < repoURL.length() - 1) {
            repoURL = repoURL.substring(0, index);
        }
        
        return repoURL.replaceAll("[^a-zA-Z0-9-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Get an empty and unique local folder to contain the local repo.
     * Uses NIO2 for efficient directory content checking.
     * @param parentFolder
     * @param repoURL
     * @return the folder
     */
    public static File getUniqueLocalFolder(File parentFolder, String repoURL) {
        String folderName = getLocalGitFolderName(repoURL);
        
        int count = 1;
        File file = new File(parentFolder, folderName);
        
        // Use NIO2 Files.list() to check if directory is non-empty (more efficient than File.list())
        while(file.exists() && isNonEmptyDirectory(file)) {
            file = new File(parentFolder, folderName + "_" + count++); //$NON-NLS-1$
        }
        
        return file;
    }
    
    /**
     * Check if a directory is non-empty using NIO2.
     * @param dir the directory to check
     * @return true if directory exists and contains at least one entry
     */
    private static boolean isNonEmptyDirectory(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir.toPath())) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            // Fall back to assuming non-empty on error
            return true;
        }
    }
    
    /**
     * Check if a folder contains a Git repository
     * @param folder
     * @return
     */
    public static boolean isGitRepository(File folder) {
        if(folder == null || !folder.exists() || !folder.isDirectory()) {
            return false;
        }
        
        File gitFolder = new File(folder, ".git"); //$NON-NLS-1$
        return gitFolder.exists() && gitFolder.isDirectory();
    }
    
    /**
     * @param model
     * @return true if a model is in a local repo folder
     */
    public static boolean isModelInLocalRepository(IArchimateModel model) {
        return getLocalRepositoryFolderForModel(model) != null;
    }
    
    /**
     * Get the enclosing local repo folder for a model
     * It is assumed that the model is located at localRepoFolder/.git/temp.archimate
     * @param model
     * @return The folder
     */
    public static File getLocalRepositoryFolderForModel(IArchimateModel model) {
        if(model == null) {
            return null;
        }
        
        File file = model.getFile();
        if(file == null || !file.getName().equals(IGraficoConstants.LOCAL_ARCHI_FILENAME)) {
            return null;
        }
        
        File parent = file.getParentFile();
        if(parent == null || !parent.getName().equals(".git")) { //$NON-NLS-1$
            return null;
        }
        
        return parent.getParentFile();
    }
    
    /**
     * @return The global user name and email as set in .gitconfig file
     * @throws IOException
     * @throws ConfigInvalidException
     */
    public static PersonIdent getGitConfigUserDetails() throws IOException, ConfigInvalidException {
        StoredConfig config = SystemReader.getInstance().openUserConfig(null, FS.detect());
        config.load();

        String name = StringUtils.safeString(config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME));
        String email = StringUtils.safeString(config.getString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL));

        return new PersonIdent(name, email);
    }
    
    /**
     * Save the gloable user name and email as set in .gitconfig file
     * @param name
     * @param email
     * @throws IOException
     * @throws ConfigInvalidException
     */
    public static void saveGitConfigUserDetails(String name, String email) throws IOException, ConfigInvalidException {
        StoredConfig config = SystemReader.getInstance().openUserConfig(null, FS.detect());
        config.load(); // It seems we have to load before save

        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME, name);
        config.setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, email);

        config.save();
    }

    /**
     * Write an object from an ObjectLoader to file using specified line ending
     * @param file File to write to
     * @param loader 
     * @param lineEnding can be "\n" or "\r\n"
     * @throws IOException
     */
    public static void writeObjectToFileWithLineEnding(File file, ObjectLoader loader, String lineEnding) throws IOException {
        String str = new String(loader.getBytes(), StandardCharsets.UTF_8);
        str = str.replaceAll("\\r?\\n", lineEnding); //$NON-NLS-1$
        Files.write(Paths.get(file.getAbsolutePath()), str.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
    }
    
    // ==================== File Counting Methods ====================
    // These use NIO2 for optimal performance compared to File.listFiles()
    
    /**
     * Count files recursively in a folder, excluding folder.xml files.
     * Uses NIO2 Files.walkFileTree for optimal performance.
     * 
     * <p>This is significantly faster than File.listFiles() because:</p>
     * <ul>
     *   <li>Uses native OS directory traversal via NIO2</li>
     *   <li>Avoids creating File objects for each entry</li>
     *   <li>Streams directory entries instead of loading all into memory</li>
     * </ul>
     * 
     * @param folder The folder to count files in
     * @return Number of files (excluding folder.xml files)
     */
    public static int countModelFilesRecursively(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return 0;
        }
        
        AtomicInteger count = new AtomicInteger(0);
        
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // Count all files except folder.xml
                        if (!file.getFileName().toString().equals(IGraficoConstants.FOLDER_XML)) {
                            count.incrementAndGet();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // Continue on failure - don't let one bad file stop counting
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            // Return what we counted so far
        }
        
        return count.get();
    }
    
    /**
     * Count regular files in a single folder (non-recursive).
     * Uses NIO2 Files.list for optimal performance.
     * 
     * @param folder The folder to count files in
     * @return Number of regular files in the folder
     */
    public static int countFilesInFolder(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return 0;
        }
        
        try (var stream = Files.list(folder)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }
    
    /**
     * Count all files recursively, including folder.xml files.
     * Used for total file count when all files matter.
     * 
     * @param folder The folder to count files in
     * @return Total number of files
     */
    public static int countAllFilesRecursively(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return 0;
        }
        
        AtomicInteger count = new AtomicInteger(0);
        
        try {
            Files.walkFileTree(folder, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        count.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                    
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            // Return what we counted so far
        }
        
        return count.get();
    }
}
