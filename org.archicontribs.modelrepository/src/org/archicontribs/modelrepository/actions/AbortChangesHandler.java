/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;


/**
 * Abort changes handler
 * 
 * @author Phillip Beauvoir
 */
public class AbortChangesHandler extends AbstractModelHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IArchiRepository repository = getActiveArchiRepository();
        
        if(repository != null) {
            AbortChangesAction action = new AbortChangesAction(HandlerUtil.getActiveWorkbenchWindowChecked(event));
            action.setRepository(repository);
            action.run();
        }
        
        return null;
    }
    
}
