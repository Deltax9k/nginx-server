package com.evun.axurerp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件上传客户端
 * 功能:
 * 用于将文件夹压缩后,上传到指定的文件服务器
 */
public class FileuploadClient {
  private static final Logger log = LoggerFactory.getLogger(FileuploadClient.class);

  //文件服务器IP地址,使用jvm启动参数指定,例如: -Dnetty.client.server.ip=10.200.146.119
  private static final String PARAM_NAME_SERVER_IP = "netty.client.server.ip";
  //文件服务器端口号,使用jvm启动参数指定,例如: -Dnetty.client.server.port=9360
  private static final String PARAM_NAME_SERVER_PORT = "netty.client.server.port";
  //文件需要上传到文件服务器的哪个相对目录下,使用jvm启动参数指定,例如: -Dnetty.client.dirname=docs
  private static final String PARAM_NAME_DIRNAME = "netty.client.dirname";
  //需要上传的文件夹绝对路径,使用jvm启动参数指定,例如: -Dnetty.client.localdirpath=/home/admin/pictures
  private static final String PARAM_NAME_LOCALDIRPATH = "netty.client.localdirpath";
  private static final String DEFAULT_DIRNAME = "home";
  private static final String DEFAULT_PORT = "9360";

  private static volatile boolean uploadSuccess = false;

  public static void main(String[] args) throws Exception {
    String localdirpath = System.getProperty(PARAM_NAME_LOCALDIRPATH);
    if (localdirpath == null || localdirpath.isEmpty()) {
      log.error("fail to upload, set jvm parameter for local directory, eg: -Dnetty.client.localdirpath=d:/mydir");
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

  /**
   * 上传指定的目录到指定的文件服务器的工作目录为根目录的某个相对目录下
   *
   * @param host      目标文件服务器IP
   * @param port      目标文件服务器端口
   * @param uploadDir 要上传的本地文件夹绝对路径
   * @param targetDir 文件服务的相对路径
   * @throws Exception
   */
  public static void upload(String host, int port,
                            final File uploadDir, final String targetDir) throws Exception {
    if (!uploadDir.exists()) {
      log.error("fail to upload, file does not exists!");
      return;
    } else if (uploadDir.isFile()) {
      log.error("fail to upload, single file not supported, upload directory instead!");
      return;
    }
    final String fileName = uploadDir.getName();
    final File uploadFile = File.createTempFile("temp", ".zip");
    ZipUtil.zip(uploadDir, uploadFile, true);
    final EventLoopGroup group = new NioEventLoopGroup();
    new Bootstrap().group(group)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.TCP_NODELAY, true)
        .handler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) throws Exception {
            ch.pipeline().addLast(new ObjectEncoder());
            ch.pipeline().addLast(new ObjectDecoder(
                ClassResolvers.weakCachingConcurrentResolver(null)));
            ch.pipeline().addLast(
                new FileUploadClientHandler(uploadFile, targetDir, fileName));
          }
        }).connect(host, port).channel().closeFuture()
        .addListener(new GenericFutureListener<Future<? super Void>>() {
          public void operationComplete(Future<? super Void> future) throws Exception {
            if (uploadSuccess) {
              boolean rm = IoUtil.rm(uploadDir);
              if (!rm && uploadDir.exists()) {
                log.info("fail to delete directory: {}", uploadFile.getAbsolutePath());
              }
            }
            if (uploadFile.exists() && !IoUtil.rm(uploadFile)) {
              log.info("fail to delete temporary zip file: {}", uploadFile.getAbsolutePath());
            }
            group.shutdownGracefully();
          }
        });
  }

  private static class FileUploadClientHandler
      extends ChannelInboundHandlerAdapter {
    private static final int BUFFER_SIZE = 1024 * 8;
    private final File file;
    private final String targetDir;
    private final String fileName;
    private final AtomicLong fileLength;

    private FileUploadClientHandler(File file, String targetDir, String fileName) {
      this.file = file;
      this.targetDir = targetDir;
      this.fileName = fileName;
      this.fileLength = new AtomicLong(file.length());
    }

    public void channelActive(final ChannelHandlerContext ctx) {
      final long start = System.currentTimeMillis();
      log.info("file: {} transfer started.", file.getAbsolutePath());
      InputStream fis = null;
      try {
        fis = Files.newInputStream(Paths.get(file.getAbsolutePath()));
        byte[] bytes;
        for (int read, position = 0;//read: 已读取的文件字节数, position: 当前总读取字节数
             (read = fis.read(bytes = new byte[BUFFER_SIZE], 0, BUFFER_SIZE)) != -1;
             position += read
            ) {
          ctx.writeAndFlush(newTransferFile(bytes, read, position))
              .addListener(newListener(ctx, start, read));
        }
      } catch (Exception e) {
        log.error(null, e);
      } finally {
        IoUtil.closeQuietly(fis);
      }
    }

    private ChannelFutureListener newListener(
        final ChannelHandlerContext ctx, final long start, final int currentRead) {

      return new ChannelFutureListener() {
        public void operationComplete(ChannelFuture future) throws Exception {
          //计算当前还需要传输的字节数
          long length = fileLength.addAndGet(-currentRead);
          if (length == 0) {
            TransferFile transferFinish = newTransferFinish();
            //发送最后的文件传输完成报文
            ctx.writeAndFlush(transferFinish)
                .addListener(new GenericFutureListener<Future<? super Void>>() {
                  public void operationComplete(Future<? super Void> future) throws Exception {
                    //设置文件传输成功标志位
                    uploadSuccess = true;
                    long end = System.currentTimeMillis();
                    log.info("file: {} transfer finished, time spent: {} ms, speed: {} m/s.",
                        file.getAbsolutePath(), end - start,
                        (file.length() * 1000 / 1024 / 1024) / (end - start));
                    if (!file.delete()) {
                      log.error("fail to deleted transfered file: {}!", file.getAbsolutePath());
                    }
                    ctx.close();
                  }
                });
          }
        }

        private TransferFile newTransferFinish() throws InterruptedException {
          TransferFile transferFile = new TransferFile();
          transferFile.setFilePath(file.getName());
          transferFile.setFileName(fileName);
          transferFile.setDeleted(false);
          //标记文件传输完成
          transferFile.setTransferFinished(true);
          transferFile.setTargetDirname(targetDir);
          return transferFile;
        }
      };
    }

    private TransferFile newTransferFile(byte[] bytes, int read, int position) {
      TransferFile transferFile = new TransferFile();
      transferFile.setFilePath(file.getName());
      transferFile.setTransferFinished(false);
      transferFile.setDeleted(false);
      transferFile.setStartPosition(position);
      transferFile.setFileBytes(bytes);
      transferFile.setByteLength(read);
      return transferFile;
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.error(null, cause);
      ctx.close();
    }
  }
}
