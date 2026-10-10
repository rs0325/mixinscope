package io.github.rs0325.mixinscope.core;

import java.io.*;

/** Checks before allocation/write; a JSON expansion cannot grow the destination past its budget. */
public final class BoundedOutputStream extends FilterOutputStream {
    private final long maximum;
    private long count;
    public BoundedOutputStream(OutputStream output,long maximum){super(output);this.maximum=maximum;}
    private void reserve(int length)throws IOException {
        if(length>maximum-count)throw new IOException("Report payload exceeds "+maximum+" UTF-8 bytes");count+=length;
    }
    @Override public void write(int value)throws IOException{reserve(1);out.write(value);}
    @Override public void write(byte[] bytes,int offset,int length)throws IOException{java.util.Objects.checkFromIndexSize(offset,length,bytes.length);reserve(length);out.write(bytes,offset,length);}
}
