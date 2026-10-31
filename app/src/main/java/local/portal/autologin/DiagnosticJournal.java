package local.portal.autologin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/** Two bounded private segments. Each accepted record is written before append returns. */
final class DiagnosticJournal {
    private static final Pattern RECORD=Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3} " +
        "(?:[a-z0-9_.:-]{1,96} -?[0-9]{1,11}|logging\\.(?:enabled|disabled|cleared))");
    private final File directory,current,previous;
    private final int limit;
    private boolean prepared;

    DiagnosticJournal(File directory) { this(directory,256*1024); }
    DiagnosticJournal(File directory,int limit) {
        if(limit<256)throw new IllegalArgumentException("journal_limit");
        this.directory=directory;this.limit=limit;
        current=new File(directory,"application.log");
        previous=new File(directory,"application.previous.log");
    }

    synchronized void append(String line) throws IOException {
        if(!RECORD.matcher(line).matches())throw new IOException("journal_record");
        if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("journal_directory");
        if(!prepared){repairTail();prepared=true;}
        byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);
        if(current.length()+bytes.length>limit && current.exists()) {
            Files.move(current.toPath(),previous.toPath(),StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        }
        try(FileOutputStream out=new FileOutputStream(current,true)) {
            // Unbuffered write survives process termination without relying on onDestroy/hooks.
            // A process crash is different from sudden loss of power to the device.
            out.write(bytes);
        } catch(IOException e){prepared=false;throw e;}
    }

    private void repairTail() throws IOException {
        if(!current.isFile() || current.length()==0)return;
        try(RandomAccessFile file=new RandomAccessFile(current,"rw")) {
            long size=file.length();file.seek(size-1);
            if(file.read()==10)return;
            long start=Math.max(0,size-512);
            byte[] tail=new byte[(int)(size-start)];file.seek(start);file.readFully(tail);
            int newline=-1;for(int i=tail.length-1;i>=0;i--)if(tail[i]==10){newline=i;break;}
            file.setLength(newline<0?0:start+newline+1);
        }
    }

    synchronized String read() throws IOException {
        StringBuilder result=new StringBuilder();
        readSegment(previous,result);readSegment(current,result);
        return result.toString();
    }
    private void readSegment(File path,StringBuilder result) throws IOException {
        if(!path.isFile())return;
        try(RandomAccessFile file=new RandomAccessFile(path,"r")) {
            file.seek(Math.max(0,file.length()-limit));
            String line;
            while((line=file.readLine())!=null) {
                // A killed writer may leave an unterminated or malformed final record.
                long end=file.getFilePointer();
                if(end==file.length()) {
                    file.seek(end-1);if(file.read()!=10)break;
                }
                if(RECORD.matcher(line).matches())result.append(line).append('\n');
            }
        }
    }
    synchronized void clear() throws IOException {
        Files.deleteIfExists(previous.toPath());Files.deleteIfExists(current.toPath());prepared=false;
    }
    synchronized void sync() throws IOException {
        if(current.isFile())try(FileOutputStream out=new FileOutputStream(current,true)){out.getFD().sync();}
    }
}
