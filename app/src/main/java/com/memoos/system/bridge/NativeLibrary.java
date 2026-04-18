package com.memoos.system.bridge;
import android.util.Log;
public class NativeLibrary {

    public static boolean NATIVE_LIBRARY_AVAILABLE;
    static{
        boolean success=true;
        try{
            System.loadLibrary("memo-native");
        }catch(UnsatisfiedLinkError e) {
            success = false;
            Log.w("Native Library","Failed to load native library, performance may decrease");
        }
        NATIVE_LIBRARY_AVAILABLE=success;
    }

    public static void load(){}//Execute `static` block

}
