package com.intellij.openapi.fileTypes;

import com.intellij.lang.Language;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.function.Supplier;

public abstract class FileTypeRegistry {
    private static Supplier<? extends FileTypeRegistry> instanceSupplier;

    public static void setInstanceSupplier(Supplier<? extends FileTypeRegistry> supplier, Disposable parentDisposable) {
        instanceSupplier = supplier;
    }

    public static Supplier<? extends FileTypeRegistry> setInstanceSupplier(Supplier<? extends FileTypeRegistry> supplier) {
        Supplier<? extends FileTypeRegistry> previous = instanceSupplier;
        instanceSupplier = supplier;
        return previous;
    }

    public static boolean isInstanceSupplierSet() {
        return instanceSupplier != null;
    }

    public static FileTypeRegistry getInstance() {
        if (instanceSupplier == null) {
            throw new IllegalStateException("FileTypeRegistry instance supplier is not set");
        }
        return instanceSupplier.get();
    }

    public abstract boolean isFileIgnored(VirtualFile file);

    public boolean isFileOfType(VirtualFile file, FileType type) {
        return getFileTypeByFile(file) == type;
    }

    public LanguageFileType findFileTypeByLanguage(Language language) {
        for (FileType fileType : getRegisteredFileTypes()) {
            if (fileType instanceof LanguageFileType && ((LanguageFileType) fileType).getLanguage() == language) {
                return (LanguageFileType) fileType;
            }
        }
        return null;
    }

    public abstract FileType[] getRegisteredFileTypes();

    public abstract FileType getFileTypeByFile(VirtualFile file);

    public FileType getFileTypeByFile(VirtualFile file, byte[] content) {
        return getFileTypeByFile(file);
    }

    public FileType getFileTypeByFileName(CharSequence fileName) {
        return getFileTypeByFileName(fileName.toString());
    }

    public abstract FileType getFileTypeByFileName(String fileName);

    public abstract FileType getFileTypeByExtension(String extension);

    public abstract FileType findFileTypeByName(String name);
}
