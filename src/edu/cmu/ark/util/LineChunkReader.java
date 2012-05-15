package edu.cmu.ark.util;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;


/**
 * Iterates through lines or line chunks (groups of nonempty lines delimited by blank lines) 
 * from a (possibly large) file. 
 * [PREVIOUSLY: This reader intented to support shuffling, i.e. randomization of the order in which
 * the lines/chunks from the underlying file are accessed, without modifying the file.
 * However, it was determined that random access of lines of Unicode files is a huge pain 
 * in Java, so this attempt was abandoned.]
 *
 * A blank line is defined as one with no non-whitespace content.
 * 
 * The file may optionally be binarized, in which case every entry is a 4-byte integer 
 * and the value 10 serves as the equivalent of a line break.
 *
 * @author Nathan Schneider (nschneid)
 * @since 2012-04-15
 */
public class LineChunkReader implements Iterable<List> {
	private int num_chunks_read = 0;
	
	private BufferedReader _rdr;	// for reading text
	private DataInputStream _din;	// for reading binary data
	private File _f;
	
	/** indices to lines in the underlying file of the start of each chunk--in the order in which they are to be accessed */
	//private List<Integer> offsets = new ArrayList<Integer>();
	//private int _i = 0;	// lines/chunks read so far in the current order
	
	private boolean is_binarized;
	private boolean omit_blanks;
	private boolean by_line;
	
	public LineChunkReader(File file) throws IOException {
		this(file, false, false, true);
	}
	
	public LineChunkReader(File file, boolean binarized) throws IOException {
		this(file, binarized, false, true);
	}
	
	/**
	 * @param file: the file to read from
	 * @param binarized: whether the file consists of binary-encoded integers
	 * @param byLine: true if reading individual lines, false for line chunks
	 * @param omitBlanks: (only applicable when reading by line) skip blank lines
	 */
	public LineChunkReader(File file, boolean binarized, boolean byLine, boolean omitBlanks) throws IOException {
		_f = file;
	
		if (binarized) {
			_din = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
			_rdr = null;
		}
		else {
			_rdr = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
			_din = null;
		}
		is_binarized = binarized;
		by_line = byLine;
		omit_blanks = omitBlanks;
	}
	
	public void close() {
		try {
			if (is_binarized) {
				if (_din!=null)
					_din.close();
				_din = null;
			} else {
				if (_rdr!=null)
					_rdr.close();
				_rdr = null;
			}
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}
	
	public void reopen() throws IOException {
		if (isOpen()) throw new IOException("LineChunkReader.reopen(): file is already open");
		if (is_binarized)
			_din = new DataInputStream(new BufferedInputStream(new FileInputStream(_f)));
		else
			_rdr = new BufferedReader(new InputStreamReader(new FileInputStream(_f)));
		num_chunks_read = 0;
	}
	
	public boolean isOpen() { return (is_binarized) ? _din!=null : _rdr!=null; }
	
	public boolean isBinarized() { return is_binarized; }
	
	private String _readLine() {
		try {
			return _rdr.readLine();
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		return null;
	}
	
	/** Reads integers up until (but not including) the value 10 or the end of the file.
	 *  Subtracts 20 from each integer.
	 */
	private int[] _readBinaryLine() {
		if (_din==null) return null;
		List<Integer> vals = new LinkedList<Integer>();
		try {
			while (true) {
				int v = _din.readInt();
				if (v==10) {
					break;
				}
				vals.add(v-20);
			}
		}
		catch (EOFException ex) { close(); }
		catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}
		
		int[] vv = new int[vals.size()];
		int i=0;
		for (int v : vals) {
			vv[i] = v;
			i++;
		}
		return vv;
	}
	
	/** Assuming the underlying file reader is at the beginning of a chunk, read until the start of the next chunk. 
	 *  When reading in line-by-line mode, each "chunk" is actually a single line.
	 */
	public List<String> readChunk() {
		List<String> chk = new ArrayList<String>();
		String ln;
		while ((ln = _readLine())!=null) {
			boolean isBlank = (ln.trim().length()==0);
			if (this.by_line && !this.omit_blanks) {
				chk.add(ln);
				break;
			}
			else if (isBlank && chk.size()==0)
				continue;	// first line of the file is blank, or multiple blanks precede this chunk
			else if (isBlank || this.by_line)
				break;
			chk.add(ln);
		}
		
		if (chk.size()==0) { return null; }
		else num_chunks_read++;
		return chk;
	}
	
	public List<int[]> readBinaryChunk() {
		List<int[]> chk = new ArrayList<int[]>();
		int[] ln;
		while ((ln = _readBinaryLine())!=null) {
			boolean isBlank = (ln.length==0);
			if (this.by_line && !this.omit_blanks) {
				chk.add(ln);
				break;
			}
			else if (isBlank && chk.size()==0)
				continue;	// first line of the file is blank, or multiple blanks precede this chunk
			else if (isBlank || this.by_line)
				break;
			chk.add(ln);
		}
		
		if (chk.size()==0) { return null; }
		else num_chunks_read++;
		return chk;
	}
	
	public String readLine() {
		if (!by_line) throw new RuntimeException("LineChunkReader.readLine(): requires line-by-line reading mode");
		List<String> chk = readChunk();
		if (chk==null) return null;
		return chk.get(0);
	}
	
	public int[] readBinaryLine() {
		if (!by_line) throw new RuntimeException("LineChunkReader.readBinaryLine(): requires line-by-line reading mode");
		List<int[]> chk = readBinaryChunk();
		if (chk==null) return null;
		return chk.get(0);
	}
	
	public int getNumChunksRead() {
		if (by_line) throw new RuntimeException("LineChunkReader.getNumChunksRead(): requires chunk reading mode");
		return num_chunks_read;
	}
	
	public int getNumLinesRead() {
		if (!by_line) throw new RuntimeException("LineChunkReader.getNumLinesRead(): requires line-by-line reading mode");
		return num_chunks_read;
	}
	
	
	
	public Iterator<List> iterator() {
		if (!isOpen()) {
			try {
				reopen();
			} catch (IOException ex) {
				ex.printStackTrace();
				System.exit(1);
			}
		}
		final LineChunkReader _chkrdr = this;
		
		return new Iterator<List>() {
			private List _chunk = null;
			public boolean hasNext() {
				if (_chunk!=null) return true;	// multiple calls to hasNext() without next()
				else if (!isOpen()) return false;
				_chunk = (is_binarized) ? _chkrdr.readBinaryChunk() : _chkrdr.readChunk();
				if (_chunk==null) close();	// close the file
				return _chunk!=null;
			}
			public List next() {
				hasNext();
				List chk = _chunk;
				_chunk = null;
				return chk;	// null if end of file
			}
			public void remove() {
				throw new RuntimeException("RandomOrderLineReader.iterator().remove() not supported");
			}
		};
	}
}