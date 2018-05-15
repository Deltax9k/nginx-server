package com.evun.axurerp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Hello world!
 *
 */
public class FileuploadServer {
  private static final Logger log = LoggerFactory.getLogger(FileuploadClient.class);

  private static final String PARAM_NAME_PORT = "netty.server.port";
  private static final String PARAM_NAME_HOME = "netty.server.home";
  private static final String DEFAULT_PORT = "9360";
  private static final String DEFAULT_HOME = "netty-fileupload-server";

  public static void main(String[] args) throws Exception {
    String portString = System.getProperty(PARAM_NAME_PORT, DEFAULT_PORT);
    if (portString == null || portString.trim().length() == 0) {
      throw new IllegalArgumentException("请设置JVM启动参数: -D" + PARAM_NAME_PORT + "=端口号");
    }
    int serverPort = Integer.parseInt(portString);
    final String homeDir = System.getProperty(PARAM_NAME_HOME, DEFAULT_HOME);
    System.out.println(
        String.format("文件服务器绑定端口: %s, 工作目录为: %s",
        serverPort, new File(homeDir).getAbsoluteFile()));
    NioEventLoopGroup boss = new NioEventLoopGroup();
    NioEventLoopGroup worker = new NioEventLoopGroup();
    try {
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
          .bind(serverPort).sync()
          .channel().closeFuture().sync();
    } finally {
      boss.shutdownGracefully();
      worker.shutdownGracefully();
    }
  }

  /**
   * Created by wq on 5/13/18.
   */
  public static class FileuploadHandler extends ChannelInboundHandlerAdapter {
    private final String homeDir;

    FileuploadHandler(String homeDir) {
      this.homeDir = homeDir;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg != null && msg instanceof TransferFile) {
        TransferFile transferFile = (TransferFile) msg;
        if (deleteIfNecessary(transferFile)) {
          return;
        }
        File file = new File(homeDir, transferFile.getFilePath());
        if (!makeParentDirIfNeccessary(file)) {
          return;
        }
        RandomAccessFile raf = null;
        try {
          raf = new RandomAccessFile(file, "rw");
          raf.seek(transferFile.getStartPosition());
          raf.write(transferFile.getFileBytes(), 0, transferFile.getByteLength());
        } finally {
          closeQuietly(raf);
        }
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      log.error(null, cause);
      ctx.close();
    }

    private void closeQuietly(Closeable closeable) {
      if (closeable != null) {
        try {
          closeable.close();
        } catch (IOException ignored) {
        }
      }
    }

    private boolean makeParentDirIfNeccessary(File file) {
      if (!file.exists()) {
        File parentFile = file.getParentFile();
        if (!parentFile.isDirectory() && !parentFile.mkdirs()) {
          log.info(String.format(
              "fail to make parent dirs for file: %s!",
              file.getAbsoluteFile()));
          return false;
        }
      }
      return true;
    }

    private boolean deleteIfNecessary(TransferFile transferFile) {
      File file = new File(homeDir, transferFile.getFilePath());
      if (transferFile.isDeleted() && file.exists()) {
        boolean delete = file.delete();
        if (!delete) {
          log.info(String.format(
              "fail to delete file: %s!",
              file.getAbsoluteFile()));
        }
      }
      return transferFile.isDeleted();
    }
  }
}
