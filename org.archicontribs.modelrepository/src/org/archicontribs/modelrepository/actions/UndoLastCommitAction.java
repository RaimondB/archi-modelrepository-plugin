/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Undo the last commit
 */
public class UndoLastCommitAction extends AbstractModelAction {
    
    private static final String TAG = "[UndoLastCommit]"; //$NON-NLS-1$
    
    public UndoLastCommitAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_UNDO_COMMIT));
        setText(Messages.UndoLastCommitAction_0);
        setToolTipText(Messages.UndoLastCommitAction_0);
    }

    @Override
    public void run() {
        // Offer to save the model if open and dirty
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            if(!offerToSaveModel(model)) {
                return;
            }
        }
        
        boolean response = MessageDialog.openConfirm(fWindow.getShell(),
                Messages.UndoLastCommitAction_0,
                Messages.UndoLastCommitAction_1);

        if(!response) {
            return;
        }
        
        GraficoModelLoader loader = new GraficoModelLoader(getRepository());
        GraficoModelImporter[] importerRef = new GraficoModelImporter[1];
        IArchimateModel[] importedModel = new IArchimateModel[1];
        Exception[] exception = new Exception[1];
        
        long t0 = System.nanoTime();
        // null parent avoids Windows WM_ACTIVATE/WM_DEACTIVATE cycle that causes maximize/restore flash
        UIPerfLogger.log(TAG, "ProgressMonitorDialog opening, shell.maximized=" + fWindow.getShell().getMaximized()); //$NON-NLS-1$
        ProgressMonitorDialog dialog = new ProgressMonitorDialog(null);
        
        try {
            dialog.run(true, false, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor pm) throws InvocationTargetException, InterruptedException {
                    SubMonitor progress = SubMonitor.convert(pm, Messages.UndoLastCommitAction_0, 100);
                    try {
                        // Phase 1: git reset --hard HEAD^ (10%)
                        getRepository().resetToRef("HEAD^"); //$NON-NLS-1$
                        progress.worked(10);
                        
                        // Phase 2: repair folder.xml if needed (5%)
                        loader.repairMissingFolderXml();
                        if(loader.hasPendingFolderMoves()) {
                            loader.applyFolderMoveResolutions();
                        }
                        progress.worked(5);
                        
                        // Phase 3: GRAFICO import (85%)
                        importerRef[0] = new GraficoModelImporter(getRepository().getLocalRepositoryFolder());
                        importedModel[0] = importerRef[0].importAsModel(progress.split(85));
                    }
                    catch(Exception ex) {
                        exception[0] = ex;
                    }
                }
            });
        }
        catch(InvocationTargetException | InterruptedException ex) {
            displayErrorDialog(Messages.UndoLastCommitAction_0, ex);
            return;
        }
        
        UIPerfLogger.log(TAG, "dialog.run() returned, shell.maximized=" + fWindow.getShell().getMaximized(), t0); //$NON-NLS-1$
        
        if(exception[0] != null) {
            displayErrorDialog(Messages.UndoLastCommitAction_0, exception[0]);
            return;
        }
        
        // UI phase: save, close/reopen editors — must run on the UI thread after dialog closes
        try {
            loader.openModel(importedModel[0], importerRef[0]);
            getRepository().saveChecksum();
        }
        catch(IOException ex) {
            displayErrorDialog(Messages.UndoLastCommitAction_0, ex);
            return;
        }
        
        UIPerfLogger.log(TAG, "run() END", t0); //$NON-NLS-1$
        
        notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
    }
    
    @Override
    protected boolean shouldBeEnabled() {
        if(!super.shouldBeEnabled()) {
            return false;
        }
        
        // If HEAD commit count is 1 then there's nothing to undo
        try(Repository repository = Git.open(getRepository().getLocalRepositoryFolder()).getRepository()) {
            try(RevWalk revWalk = new RevWalk(repository)) {
                // We are interested in the HEAD
                ObjectId objectID = repository.resolve(IGraficoConstants.HEAD);
                if(objectID == null) { // can be null!
                    revWalk.dispose();
                    return false;
                }
                
                revWalk.markStart(revWalk.parseCommit(objectID));
                
                int count = 0;
                for(@SuppressWarnings("unused") RevCommit c : revWalk) {
                    count++;
                    if(count > 1) {
                        break;
                    }
                }
                
                revWalk.dispose();
                
                if(count == 1) {
                    return false;
                }
            }
        }
        catch(IOException ex) {
            ex.printStackTrace();
        }
        
        // Otherwise...
        try {
            return !getRepository().isHeadAndRemoteSame();
        }
        catch(IOException | GitAPIException ex) {
            ex.printStackTrace();
        }
        
        return false;
    }

}
