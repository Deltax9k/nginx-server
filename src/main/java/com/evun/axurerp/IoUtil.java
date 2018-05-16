package com.evun.axurerp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by wq on 5/16/18.
 */
public abstract class IoUtil {
  private static final Logger log = LoggerFactory.getLogger(FileuploadClient.class);

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
}
