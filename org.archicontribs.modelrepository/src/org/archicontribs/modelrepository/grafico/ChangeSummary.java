/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Summary of changes to be committed
 * 
 * @author Phillip Beauvoir
 */
public class ChangeSummary {
    
    private static final int DEFAULT_MAX_ITEMS = 20;
    
    // Pattern to extract ID from filename: ElementType_id.xml (we just need everything after _)
    private static final Pattern ELEMENT_FILE_PATTERN = Pattern.compile("_([^/]+)\\.xml$"); //$NON-NLS-1$
    
    private final String summaryText;
    private final int totalChanges;
    private final int displayedChanges;
    
    private ChangeSummary(String summaryText, int totalChanges, int displayedChanges) {
        this.summaryText = summaryText;
        this.totalChanges = totalChanges;
        this.displayedChanges = displayedChanges;
    }
    
    /**
     * @return The formatted summary text suitable for commit message
     */
    public String getSummaryText() {
        return summaryText;
    }
    
    /**
     * @return Total number of changed files
     */
    public int getTotalChanges() {
        return totalChanges;
    }
    
    /**
     * @return Number of changes shown in the summary
     */
    public int getDisplayedChanges() {
        return displayedChanges;
    }
    
    /**
     * Create a change summary from git status
     * 
     * @param status Git status
     * @param repoFolder Repository folder for resolving file paths
     * @param maxItems Maximum items to display (-1 for all)
     * @return ChangeSummary
     * @throws IOException
     */
    public static ChangeSummary from(Status status, File repoFolder, int maxItems) throws IOException {
        if(maxItems <= 0) {
            maxItems = DEFAULT_MAX_ITEMS;
        }
        
        List<ChangeEntry> changes = new ArrayList<>();
        
        // Added files
        for(String path : status.getAdded()) {
            changes.add(analyzeChange('A', path, repoFolder));
        }
        
        // Modified files
        for(String path : status.getChanged()) {
            changes.add(analyzeChange('C', path, repoFolder));
        }
        
        // Deleted files
        for(String path : status.getRemoved()) {
            changes.add(analyzeChange('D', path, repoFolder));
        }
        for(String path : status.getMissing()) {
            changes.add(analyzeChange('D', path, repoFolder));
        }
        
        return buildSummary(changes, maxItems);
    }
    
    /**
     * Analyze a changed file and extract element information from XML
     */
    private static ChangeEntry analyzeChange(char changeType, String path, File repoFolder) throws IOException {
        // Check if this is a model file (under model/ directory)
        if(!path.startsWith(IGraficoConstants.MODEL_FOLDER + "/")) { //$NON-NLS-1$
            // Images or other files - just use filename
            String filename = path.substring(path.lastIndexOf('/') + 1);
            return new ChangeEntry(changeType, filename, "File", null); //$NON-NLS-1$
        }
        
        // Check if this is folder.xml (folder metadata)
        if(path.endsWith("/" + IGraficoConstants.FOLDER_XML)) { //$NON-NLS-1$
            // It's a folder - extract folder name from path
            String folderPath = path.substring(0, path.lastIndexOf('/'));
            String folderName = folderPath.substring(folderPath.lastIndexOf('/') + 1);
            return new ChangeEntry(changeType, folderName, "Folder", null); //$NON-NLS-1$
        }
        
        // Extract ID from filename (everything after the last underscore before .xml)
        String elementId = null;
        String filename = path.substring(path.lastIndexOf('/') + 1);
        
        // Try pattern match first
        Matcher matcher = ELEMENT_FILE_PATTERN.matcher(path);
        if(matcher.find()) {
            elementId = matcher.group(1);
        }
        else {
            // Pattern didn't match - try manual extraction as fallback
            // Format: ElementType_id.xml
            int underscorePos = filename.lastIndexOf('_');
            int dotPos = filename.lastIndexOf('.');
            if(underscorePos > 0 && dotPos > underscorePos) {
                elementId = filename.substring(underscorePos + 1, dotPos);
            }
        }
        
        // If we still couldn't extract an ID, return the filename
        if(elementId == null || elementId.isEmpty()) {
            return new ChangeEntry(changeType, filename, "Unknown", null); //$NON-NLS-1$
        }
        
        // For added/changed files, parse the XML to get name and type
        File xmlFile = new File(repoFolder, path);
        
        // For deleted files, file won't exist but we can try to read from git HEAD
        if(changeType == 'D' || !xmlFile.exists()) {
            // Try to read from git HEAD to get the name
            try {
                byte[] content = readFileFromGit(repoFolder, path);
                if(content != null) {
                    return parseXmlContent(changeType, content, elementId);
                }
            }
            catch(Exception e) {
                // Fall through to default handling
            }
            // Fallback: just use the ID for deleted files
            return new ChangeEntry(changeType, elementId, "Deleted Element", elementId); //$NON-NLS-1$
        }
        
        try {
            // Use SAX parser to read just the root element (fast and efficient)
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            
            RootElementHandler handler = new RootElementHandler();
            try {
                parser.parse(xmlFile, handler);
            }
            catch(SAXException e) {
                // Expected - we throw this to stop parsing after root element
                // This is normal and means we successfully read the root element
            }
            
            // Clean up the type name (remove "Archimate" prefix if present)
            String elementType = handler.getElementType();
            if(elementType.startsWith("Archimate")) { //$NON-NLS-1$
                elementType = elementType.substring(9); // Remove "Archimate" prefix
            }
            
            // Get the name attribute
            String elementName = handler.getElementName();
            if(elementName == null || elementName.isEmpty()) {
                elementName = elementId; // Fallback to ID if no name
            }
            
            return new ChangeEntry(changeType, elementName, elementType, elementId);
        }
        catch(Exception e) {
            // If XML parsing fails (file not found, malformed, etc.), return what we have
            return new ChangeEntry(changeType, elementId, "Element", elementId); //$NON-NLS-1$
        }
    }
    
    /**
     * Read a file from git HEAD (for deleted files).
     * We read from HEAD (the last commit) instead of the DirCache because
     * after staging a deletion with "git add -A", the file is removed from the index.
     */
    private static byte[] readFileFromGit(File repoFolder, String path) {
        try(Repository repository = Git.open(repoFolder).getRepository()) {
            // Git always uses forward slashes, even on Windows
            String gitPath = path.replace('\\', '/');
            
            // Resolve HEAD to get the last commit
            ObjectId headId = repository.resolve("HEAD"); //$NON-NLS-1$
            if(headId == null) {
                return null; // No commits yet
            }
            
            // Walk the commit tree to find the file
            try(RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(headId);
                RevTree tree = commit.getTree();
                
                // Find the file in the tree
                try(TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(tree);
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilter.create(gitPath));
                    
                    if(!treeWalk.next()) {
                        return null; // File not found in HEAD
                    }
                    
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    return loader.getBytes();
                }
            }
        }
        catch(Exception e) {
            // Ignore - file might not be in HEAD or git error
        }
        return null;
    }
    
    /**
     * Parse XML content from byte array (for deleted files)
     */
    private static ChangeEntry parseXmlContent(char changeType, byte[] content, String elementId) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser parser = factory.newSAXParser();
            
            RootElementHandler handler = new RootElementHandler();
            
            try (ByteArrayInputStream bis = new ByteArrayInputStream(content)) {
                parser.parse(bis, handler);
            }
            catch(SAXException e) {
                // Expected - we throw SAXException to stop parsing after root element
            }
            
            String elementType = handler.getElementType();
            String elementName = handler.getElementName();
            
            // Clean up element type by removing "Archimate" prefix if present
            if(elementType != null && elementType.startsWith("Archimate")) { //$NON-NLS-1$
                elementType = elementType.substring(9); // Remove "Archimate"
            }
            
            if(elementName == null || elementName.isEmpty()) {
                elementName = elementId; // Fallback to ID if no name
            }
            
            return new ChangeEntry(changeType, elementName, elementType, elementId);
        }
        catch(Exception e) {
            return new ChangeEntry(changeType, elementId, "Element", elementId); //$NON-NLS-1$
        }
    }
    
    /**
     * SAX handler that reads only the root element and then stops
     */
    private static class RootElementHandler extends DefaultHandler {
        private String elementType;
        private String elementName;
        private boolean rootProcessed = false;
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if(!rootProcessed) {
                // Get element type from local name (preferred with namespace-aware parsing)
                // Fall back to qName if localName is not available
                if(localName != null && !localName.isEmpty()) {
                    elementType = localName;
                }
                else if(qName != null && !qName.isEmpty()) {
                    elementType = qName;
                    // Remove namespace prefix if present (e.g., "archimate:DiagramModel" -> "DiagramModel")
                    int colonPos = elementType.indexOf(':');
                    if(colonPos > 0) {
                        elementType = elementType.substring(colonPos + 1);
                    }
                }
                else {
                    elementType = "Element"; //$NON-NLS-1$
                }
                
                // Get name attribute
                elementName = attributes.getValue("name"); //$NON-NLS-1$
                
                rootProcessed = true;
                
                // Stop parsing after root element - throw exception to halt SAX parser
                throw new SAXException("Root element processed"); //$NON-NLS-1$
            }
        }
        
        public String getElementType() {
            return elementType != null ? elementType : "Element"; //$NON-NLS-1$
        }
        
        public String getElementName() {
            return elementName;
        }
    }
    
    /**
     * Build formatted summary from change entries
     */
    private static ChangeSummary buildSummary(List<ChangeEntry> changes, int maxItems) {
        if(changes.isEmpty()) {
            return new ChangeSummary("", 0, 0); //$NON-NLS-1$
        }
        
        // Group relations separately - we'll just show a count
        Map<String, List<ChangeEntry>> relationChanges = new HashMap<>();
        List<ChangeEntry> otherChanges = new ArrayList<>();
        
        for(ChangeEntry entry : changes) {
            if(isRelationType(entry.elementType)) {
                String key = entry.changeType + "_" + entry.elementType; //$NON-NLS-1$
                relationChanges.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
            else {
                otherChanges.add(entry);
            }
        }
        
        StringBuilder sb = new StringBuilder();
        int itemCount = 0;
        int totalItems = otherChanges.size() + relationChanges.size();
        
        // Add non-relation changes (with limit)
        for(ChangeEntry entry : otherChanges) {
            if(itemCount >= maxItems) {
                break;
            }
            
            sb.append(entry.changeType).append(' ');
            sb.append(formatElementType(entry.elementType)).append(' ');
            sb.append(entry.displayName);
            sb.append('\n');
            itemCount++;
        }
        
        // Add relation counts (grouped by type)
        for(Map.Entry<String, List<ChangeEntry>> relEntry : relationChanges.entrySet()) {
            if(itemCount >= maxItems) {
                break;
            }
            
            List<ChangeEntry> rels = relEntry.getValue();
            ChangeEntry first = rels.get(0);
            
            if(rels.size() == 1) {
                // Single relation - show it
                sb.append(first.changeType).append(' ');
                sb.append(formatElementType(first.elementType));
                if(first.displayName != null) {
                    sb.append(' ').append(first.displayName);
                }
                sb.append('\n');
            }
            else {
                // Multiple relations of same type - show count
                sb.append(first.changeType).append(' ');
                sb.append(rels.size()).append(' ');
                sb.append(formatElementType(first.elementType)).append('s'); // Pluralize
                sb.append('\n');
            }
            itemCount++;
        }
        
        // Add "X of Y total" if we didn't show everything
        if(itemCount < totalItems) {
            sb.append("... ").append(itemCount).append(" of ").append(totalItems).append(" total\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        
        return new ChangeSummary(sb.toString(), changes.size(), itemCount);
    }
    
    /**
     * Check if an element type is a relation
     */
    private static boolean isRelationType(String elementType) {
        // ArchiMate relation types end with "Relationship"
        return elementType != null && elementType.endsWith("Relationship"); //$NON-NLS-1$
    }
    
    /**
     * Format element type for display (remove common prefixes/suffixes)
     */
    private static String formatElementType(String elementType) {
        if(elementType == null) {
            return "Element"; //$NON-NLS-1$
        }
        
        // Remove "ArchimateDiagramModel" suffix, show as "Diagram"
        if(elementType.equals("ArchimateDiagramModel") || elementType.equals("SketchModel")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Diagram"; //$NON-NLS-1$
        }
        
        // Remove "ArchimateModel" - it's the model itself
        if(elementType.equals("ArchimateModel")) { //$NON-NLS-1$
            return "Model"; //$NON-NLS-1$
        }
        
        // Standard element types - return as-is
        return elementType;
    }
    
    /**
     * Internal class to represent a single change
     */
    private static class ChangeEntry {
        final char changeType;      // A=Added, C=Changed, D=Deleted
        final String displayName;   // Element name or ID
        final String elementType;   // Element type (e.g., "BusinessActor")
        @SuppressWarnings("unused")
        final String elementId;     // Element ID (reserved for future use)
        
        ChangeEntry(char changeType, String displayName, String elementType, String elementId) {
            this.changeType = changeType;
            this.displayName = displayName;
            this.elementType = elementType;
            this.elementId = elementId;
        }
    }
}
