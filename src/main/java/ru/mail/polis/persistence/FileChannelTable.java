package ru.mail.polis.persistence;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.jetbrains.annotations.NotNull;

import com.google.common.collect.Iterators;

import ru.mail.polis.Iters;

public class FileChannelTable implements Table {
    private final int rows;
    private final File file;
//    private final BitSet filter;
//    private final int filterSize;

    public FileChannelTable(File file) throws IOException {
        this.file = file;
        try (FileChannel fc = openReadFileChannel()) {
            //Rows
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            assert fc != null;
            fc.read(buffer, fc.size() - Long.BYTES);

            final long rowsValue = buffer.rewind().getLong();
            assert rowsValue <= Integer.MAX_VALUE;
            this.rows = (int) rowsValue;

            //BloomFilter
//            fc.read(buffer, fc.size() - Long.BYTES - Integer.BYTES);
//            filterSize = buffer.rewind().getInt();
//            fc.read(buffer, fc.size() - Long.BYTES - Integer.BYTES - Long.BYTES * filterSize);
//            filter = new BitSet();
//            LongBuffer buf = buffer.rewind().asLongBuffer();
//
//            for (int offset = buf.position(); offset < buf.limit(); offset += Long.BYTES) {
//                filter.set((int) buf.get(offset));
//            }
        }
    }

    static void write(final Iterator<Cell> cells, final File to) throws IOException {
        try (FileChannel fc = FileChannel.open(to.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            final List<Long> offsets = new ArrayList<>();
            long offset = 0;
            BitSet bloomFilter = new BitSet();
            while (cells.hasNext()) {
                offsets.add(offset);

                final Cell cell = cells.next();

                // Key
                final ByteBuffer key = cell.getKey();
                final int keySize = cell.getKey().remaining();
                fc.write(Bytes.fromInt(keySize));
                offset += Integer.BYTES;
                fc.write(key);
                offset += keySize;

                //BloomFilter
//                BloomFilter.setKeyToFilter(bloomFilter, key);

                // Value
                final Value value = cell.getValue();

                // Timestamp
                if (value.isRemoved()) {
                    fc.write(Bytes.fromLong(-cell.getValue().getTimeStamp()));
                } else {
                    fc.write(Bytes.fromLong(cell.getValue().getTimeStamp()));
                }
                offset += Long.BYTES;

                // Value

                if (!value.isRemoved()) {
                    final ByteBuffer valueData = value.getData();
                    final int valueSize = valueData.remaining();
                    fc.write(Bytes.fromInt(valueSize));
                    offset += Integer.BYTES;
                    fc.write(valueData);
                    offset += valueSize;
                }

            }
            // Offsets
            for (long anOffset : offsets) {
                fc.write(Bytes.fromLong(anOffset));
            }

//            long[] filterArray = bloomFilter.toLongArray();
//            for (int i = 0; i < filterArray.length; i++) {
//                fc.write(Bytes.fromLong(filterArray[i]));
//            }
//            fc.write(Bytes.fromInt(filterArray.length));
            //rows
            fc.write(Bytes.fromLong(offsets.size()));
        }
    }

    public static Iterator<Cell> merge(List<FileChannelTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return new ArrayList<Cell>().iterator();
        }
        List<Iterator<Cell>> list = new ArrayList<>(tables.size());
        tables.forEach(table -> list.add(table.iterator(ByteBuffer.allocate(0))));
        Iterator<Cell> iterator = Iterators.mergeSorted(list, Cell.COMPARATOR);
        iterator = Iters.collapseEquals(iterator);
        return iterator;
    }

    public File getFile() {
        return file;
    }

    private FileChannel openReadFileChannel() {
        try {
            return FileChannel.open(file.toPath(), StandardOpenOption.READ);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private long getOffset(FileChannel fc, int i) {
        final ByteBuffer offsetBB = ByteBuffer.allocate(Long.BYTES);
        try {
            fc.read(offsetBB, fc.size()
                    - Long.BYTES
//                    - Integer.BYTES
//                    - Long.BYTES * filterSize
                    - Long.BYTES * rows
                    + Long.BYTES * i);

        } catch (IOException e) {
            e.printStackTrace();
        }
        return offsetBB.rewind().getLong();
    }

    @NotNull
    private ByteBuffer keyAt(final int i) {
        assert 0 <= i && i < rows;
        try (FileChannel fc = openReadFileChannel()) {

            ByteBuffer buffer;
            assert fc != null;
            long offset = getOffset(fc, i);
            assert offset <= Integer.MAX_VALUE;

            //KeySize
            buffer = ByteBuffer.allocate(Integer.BYTES);
            fc.read(buffer, offset);
            final int keySize = buffer.rewind().getInt();
            offset += Integer.BYTES;

            //Key
            buffer = ByteBuffer.allocate(keySize);
            fc.read(buffer, offset);
            return buffer.rewind();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private Cell cellAt(final int i) {
        assert 0 <= i && i < rows;
        try (FileChannel fc = openReadFileChannel()) {
            ByteBuffer buffer;
            assert fc != null;
            long offset = getOffset(fc, i);
            assert offset <= Integer.MAX_VALUE;

            //KeySize
            buffer = ByteBuffer.allocate(Integer.BYTES);
            fc.read(buffer, offset);
            final int keySize = buffer.rewind().getInt();
            offset += Integer.BYTES;

            //Key
            final ByteBuffer key = ByteBuffer.allocate(keySize);
            fc.read(key, offset);
            key.rewind();
            offset += keySize;

            //Timestamp
            buffer = ByteBuffer.allocate(Long.BYTES);
            fc.read(buffer, offset);
            final long timeStamp = buffer.rewind().getLong();
            offset += Long.BYTES;

            if (timeStamp < 0) {
                return new Cell(key, Value.tombstone(-timeStamp));
            }
            //valueSize
            buffer = ByteBuffer.allocate(Integer.BYTES);
            fc.read(buffer, offset);
            final int valueSize = buffer.rewind().getInt();
            offset += Integer.BYTES;

            //value
            final ByteBuffer value = ByteBuffer.allocate(valueSize);
            fc.read(value, offset);
            value.rewind();

            return new Cell(key, Value.of(timeStamp, value.slice()));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private int position(final ByteBuffer from) {
        int left = 0;
        int right = rows - 1;
        while (left <= right) {
            final int mid = left + (right - left) / 2;
            final int cmp = from.compareTo(keyAt(mid));
            if (cmp < 0) {
                right = mid - 1;
            } else if (cmp > 0) {
                left = mid + 1;
            } else {
                return mid;
            }
        }
        return left;
    }

    @Override
    public long sizeInBytes() {
        return 0;
    }

    @NotNull
    @Override
    public Iterator<Cell> iterator(@NotNull ByteBuffer from) {
        return new Iterator<>() {
            int next = position(from);

            @Override
            public boolean hasNext() {
                return next < rows;
            }

            @Override
            public Cell next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("Iterator is empty");
                }
                return cellAt(next++);
            }
        };
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) {
        throw new UnsupportedOperationException("FileTable has not access to update!");
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) {
        throw new UnsupportedOperationException("FileTable has not access to update!");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("FileTable has not access to update!");
    }
}
