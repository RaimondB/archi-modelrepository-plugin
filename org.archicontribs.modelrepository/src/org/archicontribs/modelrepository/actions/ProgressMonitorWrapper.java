/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * JGit ProgressMonitor Wrapper around a IProgressMonitor.
 * <p>
 * Delegates to {@link org.archicontribs.modelrepository.grafico.ProgressMonitorWrapper}.
 * This subclass exists for backward compatibility with code in the actions package.
 * 
 * @author Phillip Beauvoir
 */
public class ProgressMonitorWrapper extends org.archicontribs.modelrepository.grafico.ProgressMonitorWrapper {

    public ProgressMonitorWrapper(IProgressMonitor pm) {
        super(pm);
    }
}