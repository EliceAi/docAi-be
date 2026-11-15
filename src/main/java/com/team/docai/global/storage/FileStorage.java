package com.team.docai.global.storage;
import com.team.docai.global.error.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
@Component
public class FileStorage {
 private final Path root;
 public FileStorage(@Value("${docai.storage.root:./storage}") String root){this.root=Path.of(root).toAbsolutePath().normalize();}
 public byte[] toJpeg(byte[] input){
  if(input.length>20*1024*1024)throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
  try(ImageInputStream in=ImageIO.createImageInputStream(new ByteArrayInputStream(input))){
   if(in==null)throw new BusinessException(ErrorCode.INVALID_FILE);
   var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new BusinessException(ErrorCode.INVALID_FILE);
   ImageReader reader=readers.next();
   try{
    String format=reader.getFormatName();if(!format.equalsIgnoreCase("JPEG")&&!format.equalsIgnoreCase("PNG"))throw new BusinessException(ErrorCode.INVALID_FILE);
    reader.setInput(in);long pixels=(long)reader.getWidth(0)*reader.getHeight(0);
    if(pixels>40_000_000)throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
    BufferedImage src=reader.read(0);BufferedImage image=new BufferedImage(src.getWidth(),src.getHeight(),BufferedImage.TYPE_INT_RGB);
    Graphics2D g=image.createGraphics();try{g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());g.drawImage(src,0,0,null);}finally{g.dispose();}
    ByteArrayOutputStream out=new ByteArrayOutputStream();if(!ImageIO.write(image,"jpg",out))throw new IOException("JPEG encoder unavailable");return out.toByteArray();
   }finally{reader.dispose();}
  }catch(IOException|IllegalArgumentException e){throw new BusinessException(ErrorCode.INVALID_FILE);}
 }
 public String save(UUID userId,byte[] image){
  byte[] jpg=toJpeg(image);String key="contracts/"+userId+"/"+UUID.randomUUID()+".jpg";
  Path path=resolve(key);
  try{Files.createDirectories(path.getParent());Files.write(path,jpg,StandardOpenOption.CREATE_NEW);return key;}
  catch(IOException e){try{Files.deleteIfExists(path);}catch(IOException ignored){}throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);}
 }
 public byte[] read(String imageKey){try{return Files.readAllBytes(resolve(imageKey));}catch(IOException e){throw new BusinessException(ErrorCode.FILE_NOT_FOUND);}}
 public void delete(String imageKey){try{Files.deleteIfExists(resolve(imageKey));}catch(IOException e){throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);}}
 private Path resolve(String key){
  if(key==null||!key.matches("contracts/[0-9a-f-]{36}/[0-9a-f-]{36}\\.jpg"))throw new BusinessException(ErrorCode.INVALID_FILE);
  Path path=root.resolve(key).normalize();
  if(!path.startsWith(root))throw new BusinessException(ErrorCode.INVALID_FILE);
  for(Path p=path;p!=null&&p.startsWith(root);p=p.getParent())if(Files.isSymbolicLink(p))throw new BusinessException(ErrorCode.INVALID_FILE);
  return path;
 }
}
