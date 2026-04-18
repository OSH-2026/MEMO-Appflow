package com.memoos.predictor.engine;

import com.memoos.core.config.MemoConfig;
import com.memoos.core.model.AppEvent;
import com.memoos.core.model.PredictionBatch;
import com.memoos.predictor.api.Predictor;
import com.memoos.system.bridge.NativeLibrary;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.lang.ref.Cleaner;

import dalvik.annotation.optimization.CriticalNative;
import kotlin.coroutines.Continuation;
public class TransformerPredictor implements Predictor {
    private static final Cleaner CLEANER=Cleaner.create();

    static{
        NativeLibrary.load();
    }

    public TransformerPredictor(String weightsPath, String manifestPath){
        if(!NativeLibrary.NATIVE_LIBRARY_AVAILABLE){
            throw new Error("Unavailable");
        }
        pointer=constructor(weightsPath.getBytes(),manifestPath.getBytes());
        CleanTask tsk=new CleanTask(pointer);
        CLEANER.register(this,tsk);
    }

    private static class CleanTask implements Runnable{

        long ptr;

        CleanTask(long l){
            ptr=l;
        }

        @Override
        public void run(){
            TransformerPredictor.destructor(ptr);
        }

    }

    public void clear(){
        clear(pointer);
    }

    public long getEventCount(){
        return getEventCount(pointer);
    }

    private final long pointer;

    @Override
    public @NotNull String getName() {
        return "Transformer Predictor";
    }

    @Override
    public @Nullable PredictionBatch predict(@NotNull List<@NotNull AppEvent> history, @NotNull MemoConfig config, @NotNull Continuation<? super @NotNull PredictionBatch> $completion) {
        return null;
    }
    @CriticalNative
    private static native void destructor(long pointer);

    @CriticalNative
    private static native long constructor(byte[] weightsPath, byte[] manifestPath);

    @CriticalNative
    private static native void clear(long ptr);

    @CriticalNative
    private static native long getEventCount(long ptr);
}
