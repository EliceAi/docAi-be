package com.team.docai.domain.document.service;

import com.team.docai.global.error.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
@Component
public class LocalPageStore {
    private final Path root;
    public LocalPageStore(@Value("${docai.storage-path}") String path){root=Path.of(path).toAbsolutePath().normalize();}
    public void save(UUID id,List<byte[]> pages) {
        if(pages.isEmpty()||pages.size()>10)throw new BusinessException(ErrorCode.FILE_INVALID);
        long total=pages.stream().mapToLong(b->b.length).sum();if(total>25L*1024*1024)throw new BusinessException(ErrorCode.FILE_INVALID);
        try {
            List<byte[]> normalized=new ArrayList<>();
            for(byte[] bytes:pages) {
                if(bytes.length==0||bytes.length>5*1024*1024)throw new BusinessException(ErrorCode.FILE_INVALID);
                try(var in=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new BusinessException(ErrorCode.FILE_INVALID);
                    var reader=readers.next();try {reader.setInput(in);
                        String format=reader.getFormatName();if(!format.equalsIgnoreCase("png")&&!format.equalsIgnoreCase("jpeg"))throw new BusinessException(ErrorCode.FILE_INVALID);
                        int width=reader.getWidth(0),height=reader.getHeight(0);
                        if(width<1||height<1||width>5000||height>5000||(long)width*height>16000000)throw new BusinessException(ErrorCode.FILE_INVALID);
                        BufferedImage img=reader.read(0);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(img,"png",out);normalized.add(out.toByteArray());
                    } finally {reader.dispose();}
                }
            }
            Path dir=root.resolve(id.toString());Files.createDirectories(dir);
            for(int i=0;i<normalized.size();i++)Files.write(dir.resolve(i+".png"),normalized.get(i),StandardOpenOption.CREATE_NEW);
        } catch(BusinessException e){delete(id);throw e;} catch(IOException e){delete(id);throw new BusinessException(ErrorCode.FILE_STORAGE_FAILED);}
    }
    public byte[] read(UUID id,int page){try{return Files.readAllBytes(root.resolve(id.toString()).resolve(page+".png"));}catch(IOException e){throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);}}
    public void delete(UUID id){Path dir=root.resolve(id.toString());if(!Files.exists(dir))return;try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){}}
}
