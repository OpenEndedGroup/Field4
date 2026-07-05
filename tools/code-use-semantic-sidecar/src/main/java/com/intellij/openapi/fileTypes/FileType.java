package com.intellij.openapi.fileTypes;

import com.intellij.openapi.options.Scheme;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.Icon;

public interface FileType extends Scheme {
    FileType[] EMPTY_ARRAY = new FileType[0];

    @Override
    String getName();

    String getDescription();

    default String getDefaultExtension() {
        return "";
    }

    Icon getIcon();

    boolean isBinary();

    default boolean isReadOnly() {
        return false;
    }

    default String getCharset(VirtualFile file, byte[] content) {
        return null;
    }
}
