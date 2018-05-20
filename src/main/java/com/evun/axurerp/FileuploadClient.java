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
import io.netty.util.internal.SystemPropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
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
  //在上传成功后,是否删除本地文件夹,默认为是, 如果想要不删除, 则需要设置如下: -Dnetty.client.delete.localdir=false
  private static final String PARAM_NAME_DELETED_LOCALDIR = "netty.client.delete.localdir";
  private static final String DEFAULT_DIRNAME = "home";
  private static final int DEFAULT_PORT = 9360;

  public static void main(String[] args) throws Exception {
    String localdirpath = System.getProperty(PARAM_NAME_LOCALDIRPATH);
    if (localdirpath == null || localdirpath.isEmpty()) {
      log.error("文件上传失败, 未指定上传文件夹绝对路径! 请在启动参数中配置(例如): -Dnetty.client.localdirpath=d:/mydir");
      return;
    }

    uploadDirectory(
        System.getProperty(PARAM_NAME_SERVER_IP),
        SystemPropertyUtil.getInt(PARAM_NAME_SERVER_PORT, DEFAULT_PORT),
        new File(localdirpath),
        System.getProperty(PARAM_NAME_DIRNAME, DEFAULT_DIRNAME),
        System.currentTimeMillis(),
        SystemPropertyUtil.getBoolean(PARAM_NAME_DELETED_LOCALDIR, true));
  }

  /**
   * 上传指定的目录到指定的文件服务器的工作目录为根目录的某个相对目录下
   *
   * @param host      目标文件服务器IP
   * @param port      目标文件服务器端口
   * @param uploadDir 要上传的本地文件夹绝对路径
   * @param targetDir 文件服务的相对路径
   * @param startTimeMillis 开始上传的时间
   * @param deleteLocalDir 文件上传成功后,是否删除本地文件
   * @throws Exception
   */
  public static void uploadDirectory(final String host, final int port,
                                     final File uploadDir, final String targetDir,
                                     final long startTimeMillis,
                                     final boolean deleteLocalDir) throws Exception {
    if (!uploadDir.exists()) {
      log.error("上传失败! 文件夹: {} 不存在!", uploadDir.getCanonicalPath());
      return;
    } else if (uploadDir.isFile()) {
      log.error("上传终止, 只支持上传文件夹, {} 为文件而不是文件夹!", uploadDir.getCanonicalPath());
      return;
    }
    //上传成功标志位
    final AtomicBoolean uploadSuccess = new AtomicBoolean(false);
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
                new FileUploadClientHandler(uploadDir, targetDir, startTimeMillis, uploadSuccess));
          }
        }).connect(host, port)
        .addListener(new GenericFutureListener<Future<? super Void>>() {
          public void operationComplete(Future<? super Void> future) throws Exception {
            if (!future.isSuccess()) {
              Throwable cause = future.cause();
              if (cause != null && cause instanceof ConnectException) {
                log.error("文件服务器: {}:{} 无法连接, 文件上传终止! ", host, port, cause);
              }
              group.shutdownGracefully();
            } else {
              log.info("正在上传本地文件: {} 到服务器 {}:{} 的 {} 目录下...",
                  uploadDir.getCanonicalPath(), host, port, targetDir);
            }
          }
        }).channel().closeFuture()
        .addListener(new GenericFutureListener<Future<? super Void>>() {
          public void operationComplete(Future<? super Void> future) throws Exception {
            if (uploadSuccess.get() && deleteLocalDir && !XioUtil.rm(uploadDir) && uploadDir.exists()) {
              log.info("删除本地上传文件夹失败: {}", uploadDir.getCanonicalPath());
            }
            group.shutdownGracefully();
          }
        });
  }

  private static class FileUploadClientHandler
      extends ChannelInboundHandlerAdapter {
    private static final int BUFFER_SIZE = 1024 * 16;
    //上传开始时间
    private final long startTimeMillis;
    //需要上传的本地文件夹
    private final File localDir;
    //需要上传到服务器的目录(相对目录)
    private final String targetDirname;
    //当前文件的剩余还未上传的大小,为0时表示上传完成
    private final AtomicLong remainLength;
    //当前文件实际总大小(一旦设置就不改变)
    private final AtomicLong totalLength;
    private final AtomicBoolean uploadSuccess;
    //本地压缩后的临时文件名称
    private String uploadFileName;

    private FileUploadClientHandler(
        File localDir, String targetDirname,
        long startTimeMillis, AtomicBoolean uploadSuccess) {
      this.localDir = localDir;
      this.targetDirname = targetDirname;
      this.startTimeMillis = startTimeMillis;
      this.remainLength = new AtomicLong();
      this.totalLength = new AtomicLong();
      this.uploadSuccess = uploadSuccess;
    }

    public void channelActive(final ChannelHandlerContext ctx) {
      File uploadFile = null;
      InputStream fis = null;
      try {
        //将要上传的文件夹压缩,生成一个临时文件
        uploadFile = File.createTempFile("netty", ".zip");
        final File tempUploadFile = uploadFile;
        XzipUtil.zip(this.localDir, tempUploadFile);

        //初始化本地成员变量
        this.remainLength.set(tempUploadFile.length());
        this.totalLength.set(tempUploadFile.length());
        this.uploadFileName = tempUploadFile.getName();

        fis = Files.newInputStream(Paths.get(tempUploadFile.getCanonicalPath()));
        byte[] bytes;
        for (int read, position = 0;//read: 已读取的文件字节数, position: 当前总读取字节数
             (read = fis.read(bytes = new byte[BUFFER_SIZE], 0, BUFFER_SIZE)) != -1;
             position += read
            ) {
          ctx.writeAndFlush(newTransferFile(bytes, read, position))
              .addListener(newListener(ctx, read, uploadSuccess));
        }
      } catch (Exception e) {
        log.error(null, e);
      } finally {
        if (uploadFile != null && !XioUtil.rm(uploadFile)) {
          try {
            log.error("删除临时压缩文件: {} 失败!", uploadFile.getCanonicalPath());
          } catch (IOException e) {
            log.error(null, e);
          }
        }
        XioUtil.closeQuietly(fis);
      }
    }

    private ChannelFutureListener newListener(
        final ChannelHandlerContext ctx,
        final long currentWritten,
        final AtomicBoolean uploadSuccess) {

      return new ChannelFutureListener() {
        public void operationComplete(ChannelFuture future) throws Exception {
          if (!future.isSuccess()) {
            log.info("文件上传失败, 即将退出!");
            ctx.close();
            return;
          }
          //计算当前还需要传输的字节数
          if (remainLength.addAndGet(-currentWritten) == 0) {
            TransferFile transferFinish = newTransferFinish();
            //发送最后的文件传输完成报文
            ctx.writeAndFlush(transferFinish)
                .addListener(new GenericFutureListener<Future<? super Void>>() {
                  public void operationComplete(Future<? super Void> future) throws Exception {
                    if (future.isSuccess()) {
                      //设置文件传输成功标志位
                      uploadSuccess.set(true);
                      long timePeriod = System.currentTimeMillis() - startTimeMillis;
                      log.info("文件上传完成: {} (压缩大小: {} m), 耗时: {} s, 上传速度: {} m/s.",
                          localDir.getCanonicalPath(),
                          String.format("%.2f", ((double) totalLength.get()) / 1024 / 1024),
                          String.format("%.2f", ((double) timePeriod) / 1000),
                          String.format("%.2f", ((double) (totalLength.get() * 1000 / 1024 / 1024)) / timePeriod));
                    }
                    ctx.close();
                  }
                });
          }
        }

        private TransferFile newTransferFinish() throws InterruptedException {
          TransferFile transferFile = new TransferFile();
          transferFile.setFilePath(uploadFileName);
          transferFile.setFileName(localDir.getName());
          transferFile.setDeleted(false);
          //标记文件传输完成
          transferFile.setTransferFinished(true);
          transferFile.setTargetDirname(targetDirname);
          return transferFile;
        }
      };

    }

    private TransferFile newTransferFile(
        byte[] bytes, int read, int position) {
      TransferFile transferFile = new TransferFile();
      transferFile.setFilePath(uploadFileName);
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