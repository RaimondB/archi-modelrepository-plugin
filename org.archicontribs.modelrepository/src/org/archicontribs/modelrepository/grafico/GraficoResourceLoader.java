/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceImpl;

import com.archimatetool.editor.model.compatibility.IncompatibleModelException;
import com.archimatetool.editor.model.compatibility.ModelCompatibility;
import com.archimatetool.model.IIdentifier;

/**
 * Load an EObject from a file or input stream.
 * 
 * <p>Performance optimizations:</p>
 * <ul>
 *   <li>Parser features map is cached as a static immutable map (not recreated per file)</li>
 *   <li>Load options are applied efficiently without creating new maps per call</li>
 * </ul>
 */
public class GraficoResourceLoader {
    
    /**
     * Cached parser features - these are constant and don't need to be recreated for each file.
     * Using unmodifiable map to prevent accidental modification.
     */
    private static final Map<String, Object> PARSER_FEATURES;
    
    static {
        Map<String, Object> features = new HashMap<>();
        // Don't allow DTD loading in case of XSS exploits
        features.put("http://apache.org/xml/features/disallow-doctype-decl", Boolean.TRUE); //$NON-NLS-1$
        features.put("http://apache.org/xml/features/nonvalidating/load-external-dtd", Boolean.FALSE); //$NON-NLS-1$
        features.put("http://xml.org/sax/features/external-general-entities", Boolean.FALSE); //$NON-NLS-1$
        features.put("http://xml.org/sax/features/external-parameter-entities", Boolean.FALSE); //$NON-NLS-1$
        PARSER_FEATURES = Collections.unmodifiableMap(features);
    }
    
    /**
     * Cached load options - reused for all file loads to avoid per-file map creation.
     * Combines encoding and parser features in a single immutable map.
     */
    private static final Map<Object, Object> LOAD_OPTIONS;
    
    static {
        Map<Object, Object> opts = new HashMap<>();
        opts.put(XMLResource.OPTION_ENCODING, "UTF-8"); //$NON-NLS-1$
        opts.put(XMLResource.OPTION_PARSER_FEATURES, PARSER_FEATURES);
        LOAD_OPTIONS = Collections.unmodifiableMap(opts);
    }

    public static IIdentifier loadEObject(File file) throws IOException {
        XMLResource resource = new XMLResourceImpl(URI.createFileURI(file.getAbsolutePath()));
        return load(resource, null);
    }
    
    public static IIdentifier loadEObject(InputStream inputStream) throws IOException {
        XMLResource resource = new XMLResourceImpl();
        return load(resource, inputStream);
    }
    
    private static IIdentifier load(XMLResource resource, InputStream inputStream) throws IOException {
        // Load the Resource using cached options (avoids per-file map creation)
        try {
            if(inputStream != null) {
                resource.load(inputStream, LOAD_OPTIONS);
                inputStream.close();
            }
            else {
                resource.load(LOAD_OPTIONS);
            }
        }
        catch(IOException ex) {
            // No errors so must be something else
            if(resource.getErrors().isEmpty()) {
                throw ex;
            }
            // Check to see if it's an exception that is OK or not
            // Only create ModelCompatibility when we actually have errors (rare case)
            try {
                ModelCompatibility modelCompatibility = new ModelCompatibility(resource);
                modelCompatibility.checkErrors();
            }
            catch(IncompatibleModelException ex1) {
                ModelRepositoryPlugin.getInstance().log(IStatus.ERROR, "Error loading model", ex); //$NON-NLS-1$
                throw ex;
            }
        }
        
        EObject eObject = resource.getContents().get(0);
        
        if(!(eObject instanceof IIdentifier)) {
            throw new IOException("EObject has no ID"); //$NON-NLS-1$
        }
        
        // We have to remove the Eobject from its Resource so it can added to a new Resource and saved in the proper *.archimate format
        resource.getContents().remove(eObject);
        
        return (IIdentifier)eObject;
    }
}
