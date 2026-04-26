/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.authentication;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Detects whether Git Credential Manager (GCM) is available on the system.
 * GCM ships with Git for Windows and can be installed separately on macOS/Linux.
 * It handles OAuth, SSO, browser-based auth, and caches tokens in the OS credential store.
 * 
 * Detection is cached after the first call for the lifetime of the application.
 * 
 * @author coArchi
 */
public final class GitCredentialManagerDetector {
    
    private static volatile Boolean cachedResult;
    
    private GitCredentialManagerDetector() {
        // Utility class
    }
    
    /**
     * Check whether Git Credential Manager is configured as the credential helper.
     * Runs {@code git config --global credential.helper} and checks if the output
     * contains "manager" (covers "manager", "manager-core", "/usr/lib/git-core/git-credential-manager").
     * 
     * Result is cached after first successful detection.
     * 
     * @return true if GCM is available and configured, false otherwise
     */
    public static boolean isGCMAvailable() {
        Boolean result = cachedResult;
        if(result != null) {
            return result;
        }
        
        result = detectGCM();
        cachedResult = result;
        return result;
    }
    
    /**
     * Check whether native git is available on the system.
     * 
     * @return true if {@code git --version} succeeds
     */
    public static boolean isNativeGitAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "--version"); //$NON-NLS-1$ //$NON-NLS-2$
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Drain output
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while(reader.readLine() != null) { /* drain */ }
            }
            
            return process.waitFor() == 0;
        }
        catch(Exception ex) {
            return false;
        }
    }
    
    /**
     * Clear the cached detection result. Useful for testing or after user changes git config.
     */
    public static void clearCache() {
        cachedResult = null;
    }
    
    private static boolean detectGCM() {
        try {
            // Check global credential.helper setting
            ProcessBuilder pb = new ProcessBuilder("git", "config", "--global", "credential.helper"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                // No credential.helper configured globally, try system level
                return detectGCMSystem();
            }
            
            String helper = output.toString().toLowerCase();
            // GCM variants: "manager", "manager-core", full path containing "git-credential-manager"
            return helper.contains("manager"); //$NON-NLS-1$
        }
        catch(Exception ex) {
            return false;
        }
    }
    
    private static boolean detectGCMSystem() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "config", "--system", "credential.helper"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                return false;
            }
            
            String helper = output.toString().toLowerCase();
            return helper.contains("manager"); //$NON-NLS-1$
        }
        catch(Exception ex) {
            return false;
        }
    }
}
