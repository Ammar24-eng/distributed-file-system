import java.util.HashSet;
import java.util.Set;

public class FileRecord {
    public String filename;
    public long filesize;
    public String state;
    public Set<Integer> dstorePorts;

    public FileRecord(String filename, long filesize) {
        this.filename = filename;
        this.filesize = filesize;
        this.state = "store in progress";
        this.dstorePorts = new HashSet<>();
    }
}
