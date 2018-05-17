package com.evun.axurerp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * Created by wq on 5/14/18.
 */
public class FileuploadClient {
  private static final Logger log = LoggerFactory.getLogger(FileuploadClient.class);

  private static final String PARAM_NAME_SERVER_IP = "netty.client.server.ip";
  private static final String PARAM_NAME_SERVER_PORT = "netty.client.server.port";
  private static final String PARAM_NAME_DIRNAME = "netty.client.dirname";
  private static final String PARAM_NAME_LOCALDIRPATH = "netty.client.localdirpath";
  private static final String DEFAULT_DIRNAME = "home";
  private static final String DEFAULT_PORT = "9360";

  private static volatile boolean uploadSuccess = false;

  public static void main(String[] args) throws Exception {
    String localdirpath = System.getProperty(PARAM_NAME_LOCALDIRPATH);
    if (localdirpath == null || localdirpath.isEmpty()) {
      log.error("fail to upload, set jvm paramter for local directory: -Dnetty.client.localdirpath=d:/mydir");
      return;
    }
    String serverip = System.getProperty(PARAM_NAME_SERVER_IP);
    int port = Integer.parseInt(System.getProperty(PARAM_NAME_SERVER_PORT, DEFAULT_PORT));
    File localdir = new File(localdirpath);
    String targetDirname = System.getProperty(PARAM_NAME_DIRNAME, DEFAULT_DIRNAME);
    log.info("uploading local directory: {} to server {}:{}, target home directory: {}",
        localdir.getAbsolutePath(), serverip, port, targetDirname);
    upload(serverip, port, localdir, targetDirname);
  }

  public static void upload(String host, int port, final File file, final String targetDir) throws Exception {
    if (!file.exists()) {
      log.error("fail to upload, file does not exists!");
      return;
    }
    File temp = file;
    if (file.isDirectory()) {
      File tempFile = File.createTempFile("temp", "." + file.getName() + ".zip");
      ZipUtil.zip(file, tempFile, true);
      temp = tempFile;
    }
    final File uploadFile = temp;
    EventLoopGroup group = new NioEventLoopGroup(2);
    try {
      Bootstrap b = new Bootstrap();
      b.group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true).handler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) throws Exception {
          ch.pipeline().addLast(new ObjectEncoder());
          ch.pipeline().addLast(new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(null)));
          ch.pipeline().addLast(new FileUploadClientHandler(uploadFile, targetDir));
        }
      }).connect(host, port).channel().closeFuture().sync();
    } finally {
      group.shutdownGracefully();
      //临时文件需要在退出时删除
      //tempFile.deleteOnExit();
      if (uploadSuccess) {
        boolean rm = IoUtil.rm(file);
        if (!rm) {
          log.info("fail to delete directory: {}", file.getAbsolutePath());
        }
      }
    }
  }

  private static class FileUploadClientHandler
      extends ChannelInboundHandlerAdapter {
    private static final int BUFFER_SIZE = 1024 * 8;
    private final File file;
    private final String targetDir;

    private FileUploadClientHandler(File file, String targetDir) {
      this.file = file;
      this.targetDir = targetDir;
    }

    public void channelActive(final ChannelHandlerContext ctx) {
      final long start = System.currentTimeMillis();
      log.info("file: {} transfer started.", file.getAbsolutePath());
      InputStream fis = null;
      try {
        fis = Files.newInputStream(Paths.get(file.getAbsolutePath()));
        byte[] bytes;
        final CountDownLatch countDownLatch = new CountDownLatch(
            (int) ((file.length() + BUFFER_SIZE - 1) / BUFFER_SIZE));
        final String name = file.getName();
        int read;
        for (int position = 0;
             (read = fis.read(bytes = new byte[BUFFER_SIZE], 0, BUFFER_SIZE)) != -1;
             position += read
            ) {
          //log.info("file length: " + file.length());
          TransferFile transferFile = new TransferFile();
          transferFile.setFilePath(name);
          transferFile.setTransferFinished(false);
          transferFile.setDeleted(false);
          transferFile.setStartPosition(position);
          log.info("total bytes: {}, current length: {}, current position: {}.", (position + read), read, position);
          transferFile.setFileBytes(bytes);
          transferFile.setByteLength(read);
          final int currentPosition = position;
          ctx.writeAndFlush(transferFile).addListener(new ChannelFutureListener() {
            public void operationComplete(ChannelFuture future) throws Exception {
//              log.info(countDownLatch.getCount() + "");
              log.info("file: {} uploading progress: {}% ({} / {})",
                  file.getAbsolutePath(), (currentPosition * 100 / file.length()), currentPosition, file.length());
              countDownLatch.countDown();
            }
          });
        }

        log.info("read: " + read);

        new Thread(new Runnable() {
          public void run() {
            try {
              countDownLatch.await();

              TransferFile transferFile = new TransferFile();
              transferFile.setFilePath(name);
              transferFile.setDeleted(false);
              transferFile.setTransferFinished(true);
              transferFile.setTargetDirname(targetDir);
              ctx.writeAndFlush(transferFile).sync();
              //log.info("total bytes: " + (position + read) + ", current length: " + read);

              uploadSuccess = true;

              long end = System.currentTimeMillis();
              log.info("file: {} transfer finished, time spent: {} ms, speed: {} m/s.",
                  file.getAbsolutePath(),
                  end - start,
                  (file.length() / 1024 / 1024) * 1000 / (end - start));
            } catch (Exception e) {
              log.error(null, e);
            } finally {
              ctx.close();
              if (!file.delete()) {
                log.error("fail to deleted transfered file: {}!", file.getAbsolutePath());
              }
            }
          }
        }).start();
      } catch (Exception e) {
        log.error(null, e);
      } finally {
        IoUtil.closeQuietly(fis);
      }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.error(null, cause);
      ctx.close();
    }
  }
}
