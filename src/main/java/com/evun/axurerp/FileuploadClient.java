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

import java.io.*;

/**
 * Created by wq on 5/14/18.
 */
public class FileuploadClient {
  private static final Logger log = LoggerFactory.getLogger(FileuploadClient.class);

  public static void main(String[] args) throws Exception {
    upload("127.0.0.1", 8080,
//        new File("/Users/wq/docs/AkkaScala.pdf"));
        new File("/Users/wq/docs/易云科技报到电子材料"));
  }

  public static void upload(String host, int port, final File file) throws Exception {
    if (!file.exists()) {
      log.error("fail to upload, file do not exists!");
      return;
    }
    File temp = file;
    if (file.isDirectory()) {
      File tempFile = File.createTempFile("netty", "temp");
      ZipUtil.zip(file, tempFile, true);
      temp = tempFile;
    }
    final File uploadFile = temp;
    EventLoopGroup group = new NioEventLoopGroup();
    try {
      Bootstrap b = new Bootstrap();
      b.group(group).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true).handler(new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) throws Exception {
          ch.pipeline().addLast(new ObjectEncoder());
          ch.pipeline().addLast(new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(null)));
          ch.pipeline().addLast(new FileUploadClientHandler(uploadFile));
        }
      });
      ChannelFuture f = b.connect(host, port).sync();
      f.channel().closeFuture().sync();
    } finally {
      group.shutdownGracefully();
    }
  }

  private static class FileUploadClientHandler
      extends ChannelInboundHandlerAdapter {
    private static final int BUFFER_SIZE = 1024 * 8;
    private final File file;

    private FileUploadClientHandler(File file) {
      this.file = file;
    }

    public void channelActive(ChannelHandlerContext ctx) {
      BufferedInputStream bis = null;
      try {
        bis = new BufferedInputStream(new FileInputStream(file));
        byte[] bytes = null;
        String name = file.getName();
        for (int read = -1, position = 0;
             (read = bis.read(bytes = new byte[BUFFER_SIZE])) != -1;
             position += read
            ) {
          TransferFile transferFile = new TransferFile();
          transferFile.setFilePath(name);
          transferFile.setDeleted(false);
          if ((file.length() - 2 * BUFFER_SIZE) < position) {
            System.out.println();
          }
          transferFile.setStartPosition(position);
          transferFile.setFileBytes(bytes);
          transferFile.setByteLength(read);
          ctx.writeAndFlush(transferFile);
        }
      } catch (Exception e) {
        log.error(null, e);
      } finally {
        if (bis != null) {
          try {
            bis.close();
          } catch (IOException e) {
            log.error(null, e);
          }
        }
      }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.error(null, cause);
      ctx.close();
    }
  }
}
