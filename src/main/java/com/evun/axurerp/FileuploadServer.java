package com.evun.axurerp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 文件上传服务器端
 * 功能:
 * 将上传的文件解压后,放到指定目录的指定文件夹内
 */
public class FileuploadServer {
  private static final Logger log = LoggerFactory.getLogger(FileuploadServer.class);

  //服务器运行的端口号,使用jvm启动参数指定,例如: -Dnetty.server.port=9360
  private static final String PARAM_NAME_PORT = "netty.server.port";
  //服务器的工作目录,所有上传的文件将放在这个目录下, 使用jvm启动参数指定,例如: -Dnnetty.server.home=/home/admin/book
  private static final String PARAM_NAME_HOME = "netty.server.home";
  //服务器默认端口号
  private static final String DEFAULT_PORT = "9360";
  //服务器默认工作目录,最好使用绝对路径
  private static final String DEFAULT_HOME = "netty-fileupload-server";

  public static void main(String[] args) throws Exception {
    String portString = System.getProperty(PARAM_NAME_PORT, DEFAULT_PORT);
    if (portString == null || portString.trim().length() == 0) {
      throw new IllegalArgumentException("请设置JVM启动参数: -D" + PARAM_NAME_PORT + "=端口号");
    }
    final int serverPort = Integer.parseInt(portString);
    final String homeDir = System.getProperty(PARAM_NAME_HOME, DEFAULT_HOME);
    final String workingDir = new File(homeDir).getCanonicalPath();
    final NioEventLoopGroup boss = new NioEventLoopGroup();
    final NioEventLoopGroup worker = new NioEventLoopGroup();
    new ServerBootstrap()
        .group(boss, worker)
        .channel(NioServerSocketChannel.class)
        .option(ChannelOption.SO_BACKLOG, 1024)
        .childHandler(new ChannelInitializer<Channel>() {
          protected void initChannel(Channel channel) throws Exception {
            channel.pipeline()
                .addLast(new ObjectEncoder())
                .addLast(new ObjectDecoder(Integer.MAX_VALUE,
                    ClassResolvers.weakCachingConcurrentResolver(null)))
                .addLast(new FileuploadHandler(homeDir));
          }
        })
        .bind(serverPort)
        .addListener(new GenericFutureListener<Future<? super Void>>() {
          public void operationComplete(Future<? super Void> future) throws Exception {
            log.info("文件服务器启动成功! 绑定端口: {}, 工作目录为: {}", serverPort, workingDir);
          }
        })
        .channel().closeFuture()
        .addListener(new GenericFutureListener<Future<? super Void>>() {
          public void operationComplete(Future<? super Void> future) throws Exception {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            log.info("文件服务器(端口号: {}, 工作目录: {}) 已关闭!", serverPort, workingDir);
          }
        });
  }

  /**
   * 文件服务器处理逻辑
   */
  public static class FileuploadHandler extends ChannelInboundHandlerAdapter {
    //文件服务器工作目录
    private final String homeDir;

    FileuploadHandler(String homeDir) {
      this.homeDir = homeDir;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg != null && msg instanceof TransferFile) {
        TransferFile transferFile = (TransferFile) msg;
        File file = new File(homeDir, transferFile.getFilePath());
        //文件传输完成后,客户端会将transferFinished设置为true
        if (!transferFile.isTransferFinished()) {
          //将客户端上传的文件块写入指定文件
          writeTranferFile(transferFile, file);
        } else {
          //将上传完成后的文件移入目标目录中
          unzipMoveDir(transferFile, file);
        }
      }
    }

    private void unzipMoveDir(TransferFile transferFile, File file) throws Exception {
      XzipUtil.unzip(file, new File(homeDir));
      if (!file.delete()) {
        log.error("删除已上传的压缩文件失败: {}", file.getCanonicalPath());
      }
      File unzipDir = new File(homeDir, transferFile.getFileName());
      if (unzipDir.isDirectory()) {
        File oldDir = new File(homeDir, transferFile.getTargetDirname());
        if (oldDir.exists() && !XioUtil.rm(oldDir)) {
          log.error("删除旧的文件夹失败: {}", oldDir.getCanonicalPath());
        }
        if (unzipDir.renameTo(oldDir)) {
          log.info("成功更新文件夹: {}", oldDir.getCanonicalPath());
        } else {
          log.error("更新文件夹失败: {}", oldDir.getCanonicalPath());
        }
      }
    }

    private void writeTranferFile(TransferFile transferFile, File file) throws IOException {
      if (deleteIfNecessary(transferFile)) {
        return;
      }
      if (!makeParentDirIfNeccessary(file)) {
        return;
      }
      RandomAccessFile raf = null;
      try {
        raf = new RandomAccessFile(file, "rw");
        raf.seek(transferFile.getStartPosition());
        raf.write(transferFile.getFileBytes(), 0, transferFile.getByteLength());
      } finally {
        XioUtil.closeQuietly(raf);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      log.error(null, cause);
      ctx.close();
    }

    private boolean makeParentDirIfNeccessary(File file) {
      if (!file.exists()) {
        File parentFile = file.getParentFile();
        if (!parentFile.isDirectory() && !parentFile.mkdirs()) {
          log.info("fail to make parent dirs for file: {}!", file.getAbsoluteFile());
          return false;
        }
      }
      return true;
    }

    private boolean deleteIfNecessary(TransferFile transferFile) {
      File file = new File(homeDir, transferFile.getFilePath());
      if (transferFile.isDeleted() && file.exists() && !XioUtil.rm(file)) {
        log.info("fail to delete file or directory: {}!", file.getAbsoluteFile());
      }
      return transferFile.isDeleted();
    }
  }
}
