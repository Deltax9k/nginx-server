/*
 * Copyright 2009-2012 Evun Technology.
 *
 * This software is the confidential and proprietary information of
 * Evun Technology. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with evun.cn.
 */
package com.evun.axurerp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * ZipUtil
 *
 * @author ZENG.XIAO.YAN && ni.minjie
 * @version v1.0
 * @date 2018年5月19日 下午7:16:08
 */
public abstract class ZipUtil {
    private static final Logger log = LoggerFactory.getLogger(ZipUtil.class);

    private static final int BUFFER_SIZE = 2 * 1024;

    /**
     * 压缩成ZIP 方法1
     *
     * @param srcDirOrFile           压缩文件夹路径
     * @param destFile              目标压缩文件
     * @param keepDirStructure 是否保留原来的目录结构,true:保留目录结构;
     *                         false:所有文件跑到压缩包根目录下(注意：不保留目录结构可能会出现同名文件,会压缩失败)
     * @throws RuntimeException 压缩失败会抛出运行时异常
     */
    public static void zip(File srcDirOrFile, File destFile, boolean keepDirStructure) {
        try {
            zip(srcDirOrFile, new BufferedOutputStream(new FileOutputStream(destFile)), keepDirStructure);
        } catch (FileNotFoundException e) {
            log.info(null, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 解压缩zip包
     *
     * @param zipFile        zip文件的全路径
     * @param unzipDir      解压后的文件保存的路径
     */
    @SuppressWarnings("unchecked")
    public static void unzip(File zipFile, File unzipDir) throws Exception {
        long start = System.currentTimeMillis();
        assertTrue(zipFile.isFile());
        assertTrue(unzipDir.isDirectory() || unzipDir.mkdirs());
        //开始解压
        ZipFile zip = new ZipFile(zipFile);
        byte[] buffer = new byte[BUFFER_SIZE];
        Enumeration<ZipEntry> entries = (Enumeration<ZipEntry>) zip.entries();
        //循环对压缩包里的每一个文件进行解压
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            //构建压缩包中一个文件解压后保存的文件全路径
            File file = new File(unzipDir, entry.getName());
            if (entry.isDirectory()) {
                assertTrue(((file.exists() && file.isDirectory()) || file.mkdirs()));
            } else {
                File parentFile = file.getParentFile();
                assertTrue(((parentFile.exists() && parentFile.isDirectory()) || parentFile.mkdirs()));
                BufferedOutputStream bos = null;
                BufferedInputStream bis = null;
                try {
                    bos = new BufferedOutputStream(new FileOutputStream(file));
                    bis = new BufferedInputStream(zip.getInputStream(entry));
                    int read;
                    while ((read = bis.read(buffer)) != -1) {
                        bos.write(buffer, 0, read);
                    }
                } finally {
                    closeQuietly(bos);
                    closeQuietly(bis);
                }
            }
        }
        log.info(String.format(
                "解压缩完成，耗时：%s ms, 目录为：%s",
                System.currentTimeMillis() - start,
                unzipDir.getAbsolutePath()
        ));
    }

    private static void assertTrue(boolean expression) {
        if (!expression) {
            throw new AssertionError();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            if (closeable instanceof InputStream) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.info(null, e);
                }
            }
            if (closeable instanceof OutputStream) {
                OutputStream outputStream = (OutputStream) closeable;
                try {
                    outputStream.flush();
                } catch (Exception e) {
                    log.info(null, e);
                }
                try {
                    outputStream.close();
                } catch (Exception e) {
                    log.info(null, e);
                }
            }
        }
    }

    private static void zip(File sourceFile, OutputStream out, boolean keepDirStructure)
            throws RuntimeException {

        long start = System.currentTimeMillis();
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(out);
            compress(sourceFile, zos, sourceFile.getName(), keepDirStructure);
            log.info("压缩完成，耗时：" + (System.currentTimeMillis() - start) + " ms");
        } catch (Exception e) {
            log.info(null, e);
            throw new RuntimeException("zip info from ZipUtils", e);
        } finally {
            closeQuietly(zos);
        }
    }

    /**
     * 压缩成ZIP 方法2
     *
     * @param srcFiles 需要压缩的文件列表
     * @param out      压缩文件输出流
     * @throws RuntimeException 压缩失败会抛出运行时异常
     */
    public static void zip(List<File> srcFiles, OutputStream out) throws RuntimeException {
        long start = System.currentTimeMillis();
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(out);
            byte[] buf = new byte[BUFFER_SIZE];
            for (File srcFile : srcFiles) {
                zos.putNextEntry(new ZipEntry(srcFile.getName()));
                writeEntry(zos, buf, srcFile);
            }
            log.info("压缩完成，耗时：" + (System.currentTimeMillis() - start) + " ms");
        } catch (Exception e) {
            log.info(null, e);
            throw new RuntimeException("zip info from ZipUtils", e);
        } finally {
            closeQuietly(zos);
        }
    }

    private static void writeEntry(ZipOutputStream zos, byte[] buf, File srcFile) {
        int len;
        FileInputStream in = null;
        try {
            in = new FileInputStream(srcFile);
            while ((len = in.read(buf)) != -1) {
                zos.write(buf, 0, len);
            }
        } catch (Exception e) {
            log.info(null, e);
            throw new RuntimeException(e);
        } finally {
            closeQuietly(in);
            closeEntryQuietly(zos);
        }
    }

    private static void closeEntryQuietly(ZipOutputStream zos) {
        if (zos != null) {
            try {
                zos.flush();
            } catch (Exception e) {
                log.info(null, e);
            }
            try {
                zos.closeEntry();
            } catch (Exception e) {
                log.info(null, e);
            }
        }
    }

    /**
     * 递归压缩方法
     *
     * @param sourceFile       源文件
     * @param zos              zip输出流
     * @param name             压缩后的名称
     * @param keepDirStructure 是否保留原来的目录结构,true:保留目录结构;
     *                         false:所有文件跑到压缩包根目录下(注意：不保留目录结构可能会出现同名文件,会压缩失败)
     * @throws Exception
     */
    private static void compress(File sourceFile, ZipOutputStream zos, String name,
                                 boolean keepDirStructure) throws Exception {
        byte[] buf = new byte[BUFFER_SIZE];
        if (sourceFile.isFile()) {
            // 向zip输出流中添加一个zip实体，构造器中name为zip实体的文件的名字
            zos.putNextEntry(new ZipEntry(name));
            // copy文件到zip输出流中
            int len;
            FileInputStream in = null;
            try {
                in = new FileInputStream(sourceFile);
                while ((len = in.read(buf)) != -1) {
                    zos.write(buf, 0, len);
                }
            } finally {
                closeQuietly(in);
                closeEntryQuietly(zos);
            }
        } else {
            File[] listFiles = sourceFile.listFiles();
            if (listFiles == null || listFiles.length == 0) {
                // 需要保留原来的文件结构时,需要对空文件夹进行处理
                if (keepDirStructure) {
                    // 空文件夹的处理
                    zos.putNextEntry(new ZipEntry(name + "/"));
                    // 没有文件，不需要文件的copy
                    closeEntryQuietly(zos);
                }

            } else {
                for (File file : listFiles) {
                    // 判断是否需要保留原来的文件结构
                    if (keepDirStructure) {
                        // 注意：file.getName()前面需要带上父文件夹的名字加一斜杠,
                        // 不然最后压缩包中就不能保留原来的文件结构,即：所有文件都跑到压缩包根目录下了
                        compress(file, zos, name + File.separator + file.getName(), keepDirStructure);
                    } else {
                        compress(file, zos, file.getName(), keepDirStructure);
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        /** 测试压缩方法1  */
        File src = new File("/home/wq/project/gpmc");
        File file = new File("/home/wq/Documents/gpmc.zip");
        ZipUtil.zip(src, file, true);
        File dest = new File("/home/wq/Documents/nc");
        ZipUtil.unzip(file, dest);
        /** 测试压缩方法2  */
//        List<File> fileList = new ArrayList<>();
//        fileList.add(new File("D:/Java/jdk1.7.0_45_64bit/bin/jar.exe"));
//        fileList.add(new File("D:/Java/jdk1.7.0_45_64bit/bin/java.exe"));
//        FileOutputStream fos2 = new FileOutputStream(new File("c:/mytest02.zip"));
//        ZipUtil.zip(fileList, fos2);
    }
}
