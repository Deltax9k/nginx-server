package com.evun.axurerp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.OutputStream;

/**
 * Created by wq on 5/16/18.
 */
public abstract class IoUtil {
  private static final Logger log = LoggerFactory.getLogger(IoUtil.class);

  public static void closeQuietly(Closeable closeable) {
    if (closeable == null) {
      return;
    } else if (closeable instanceof OutputStream) {
      try {
        ((OutputStream) closeable).flush();
      } catch (Exception e) {
        log.debug(null, e);
      }
    }
    try {
      closeable.close();
    } catch (Exception e) {
      log.debug(null, e);
    }
  }

  /**
   * 删除文件或者文件夹，只有当所有的文件都删除成功，才返回true
   *
   * @param file
   * @return
   */
  public static boolean rm(File file) {
    if (file == null || !file.exists()) {
      return false;
    } else if (file.isFile()) {
      return file.delete();
    } else {
      File[] children = file.listFiles();
      if (children == null || children.length == 0) {
        return file.delete();
      } else {
        boolean success = true;
        for (File eachFile : children) {
          if (success) {
            success = rm(eachFile);
          }
        }
        return file.delete() && success;
      }
    }
  }
}
