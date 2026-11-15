package com.team.docai.global.auth;
import com.team.docai.global.error.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
@Component
public class AnonymousRateLimiter {
 private final Clock clock;
 private final Map<String,Window> windows=new HashMap<>();
 public AnonymousRateLimiter(){this(Clock.systemUTC());}
 AnonymousRateLimiter(Clock clock){this.clock=clock;}
 public synchronized void check(String address){
  Instant now=clock.instant();
  windows.entrySet().removeIf(e->!now.isBefore(e.getValue().start.plusSeconds(60)));
  Window window=windows.get(address);
  if(window==null){
   if(windows.size()>=10000)throw new BusinessException(ErrorCode.RATE_LIMITED);
   window=new Window(now);windows.put(address,window);
  }
  if(window.count>=10)throw new BusinessException(ErrorCode.RATE_LIMITED);
  window.count++;
 }
 private static class Window {final Instant start;int count;Window(Instant start){this.start=start;}}
}
