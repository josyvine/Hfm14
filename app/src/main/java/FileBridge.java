package com.hfm.app;

import java.util.ArrayList;

public class FileBridge {
    /**
     * Safely holds massive lists (100,000+ items) in memory to prevent 
     * android.os.TransactionTooLargeException when passing data to the DeleteService.
     * 
     * The Activity sets this list, and the Service reads and clears it.
     */
    public static ArrayList<String> mFilesToDelete = new ArrayList<>();
}