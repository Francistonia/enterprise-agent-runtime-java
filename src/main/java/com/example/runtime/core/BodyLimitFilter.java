package com.example.runtime.core;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BodyLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT=2*1024*1024;
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(request.getContentLengthLong()>LIMIT) {response.sendError(413);return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;
            @Override public ServletInputStream getInputStream() throws IOException {
                if(limited==null) {
                    var original=super.getInputStream();
                    limited=new ServletInputStream() {
                        int total;
                        public int read() throws IOException {
                            int value=original.read();
                            if(value!=-1 && ++total>LIMIT) throw new IOException("Request body exceeds size limit");
                            return value;
                        }
                        public int read(byte[] bytes,int offset,int length) throws IOException {
                            int count=original.read(bytes,offset,Math.min(length,LIMIT-total+1));
                            if(count>0 && (total+=count)>LIMIT)throw new IOException("Request body exceeds size limit");
                            return count;
                        }
                        public boolean isFinished(){return original.isFinished();}
                        public boolean isReady(){return original.isReady();}
                        public void setReadListener(ReadListener listener){original.setReadListener(listener);}
                    };
                }
                return limited;
            }
        },response);
    }
}
