package com.nstorm2.engine.cpk;

import com.nstorm2.engine.io.BinaryReader;
import com.nstorm2.engine.io.FormatException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CRI "@UTF" table (used by CPK headers and tables of contents).
 *
 * Layout (big-endian, offsets relative to byte 8):
 * <pre>
 * 00 "@UTF"   04 u32 size   08 u8 ?  09 u8 encoding (0 = Shift-JIS)
 * 0A u16 rowsOffset  0C u32 stringPoolOffset  10 u32 dataPoolOffset
 * 14 u32 tableNameOffset  18 u16 columnCount  1A u16 rowSize  1C u32 rowCount
 * 20 columns: u8 flags|type [u32 nameOffset] [default value]
 * </pre>
 * Column flags: 0x10 has name, 0x20 has default value, 0x40 stored per row.
 * Encrypted tables (first word not "@UTF") are XOR-decrypted with the standard CRI
 * keystream (start 0x5F, multiplier 0x15).
 */
public final class UtfTable {
    public static final int TYPE_U8 = 0, TYPE_S8 = 1, TYPE_U16 = 2, TYPE_S16 = 3, TYPE_U32 = 4, TYPE_S32 = 5,
            TYPE_U64 = 6, TYPE_S64 = 7, TYPE_F32 = 8, TYPE_F64 = 9, TYPE_STRING = 10, TYPE_DATA = 11, TYPE_GUID = 12;

    private static final int FLAG_NAME = 0x10, FLAG_DEFAULT = 0x20, FLAG_ROW = 0x40;

    public static final class Column {
        public final String name;
        public final int type;
        public final int flags;
        final int defaultOffset; // relative to table, or -1
        int rowOffset;           // byte offset within a row, or -1

        Column(String name, int type, int flags, int defaultOffset) {
            this.name = name;
            this.type = type;
            this.flags = flags;
            this.defaultOffset = defaultOffset;
        }
    }

    private final BinaryReader table;
    private final String name;
    private final List<Column> columns;
    private final int rowsOffset;
    private final int rowSize;
    private final int rowCount;
    private final int stringPool;
    private final int dataPool;
    private final Charset charset;

    public static boolean isEncrypted(byte[] t) {
        return t.length >= 4 && !(t[0] == '@' && t[1] == 'U' && t[2] == 'T' && t[3] == 'F');
    }

    public static void decryptInPlace(byte[] t) {
        int xor = 0x5F;
        for (int i = 0; i < t.length; i++) {
            t[i] = (byte) (t[i] ^ xor);
            xor = (xor * 0x15) & 0xFF;
        }
    }

    public static UtfTable parse(byte[] raw) {
        byte[] t = raw;
        if (isEncrypted(t)) {
            t = raw.clone();
            decryptInPlace(t);
            if (isEncrypted(t)) throw new FormatException("not an @UTF table");
        }
        return new UtfTable(t);
    }

    private UtfTable(byte[] t) {
        BinaryReader r = new BinaryReader(t, true);
        r.seek(0x09);
        int encoding = r.u8();
        charset = encoding == 0 ? BinaryReader.SHIFT_JIS : StandardCharsets.UTF_8;
        rowsOffset = r.u16() + 8;
        stringPool = (int) r.u32() + 8;
        dataPool = (int) r.u32() + 8;
        int nameOffset = (int) r.u32();
        int columnCount = r.u16();
        rowSize = r.u16();
        rowCount = (int) r.u32();
        table = r;

        name = safeString(stringPool + nameOffset);

        List<Column> cols = new ArrayList<>(columnCount);
        r.seek(0x20);
        int rowCursor = 0;
        for (int i = 0; i < columnCount; i++) {
            int flagType = r.u8();
            int type = flagType & 0x0F;
            int flags = flagType & 0xF0;
            String colName = "";
            if ((flags & FLAG_NAME) != 0) colName = safeString(stringPool + (int) r.u32());
            int def = -1;
            if ((flags & FLAG_DEFAULT) != 0) {
                def = r.position();
                r.skip(valueSize(type));
            }
            Column c = new Column(colName, type, flags, def);
            if ((flags & FLAG_ROW) != 0) {
                c.rowOffset = rowCursor;
                rowCursor += valueSize(type);
            } else {
                c.rowOffset = -1;
            }
            cols.add(c);
        }
        columns = Collections.unmodifiableList(cols);
    }

    private String safeString(int offset) {
        try {
            return table.cStringAt(offset, charset);
        } catch (FormatException e) {
            return "";
        }
    }

    static int valueSize(int type) {
        switch (type) {
            case TYPE_U8: case TYPE_S8: return 1;
            case TYPE_U16: case TYPE_S16: return 2;
            case TYPE_U32: case TYPE_S32: case TYPE_F32: case TYPE_STRING: return 4;
            case TYPE_U64: case TYPE_S64: case TYPE_F64: case TYPE_DATA: return 8;
            case TYPE_GUID: return 16;
            default: throw new FormatException("unknown @UTF column type " + type);
        }
    }

    public String name() { return name; }
    public int rowCount() { return rowCount; }
    public List<Column> columns() { return columns; }

    public Column column(String colName) {
        for (Column c : columns) if (c.name.equals(colName)) return c;
        return null;
    }

    public boolean has(String colName) {
        return column(colName) != null;
    }

    /** Offset (within the table) of the value of a column for a row, or -1 if the column has no value. */
    private int valueOffset(Column c, int row) {
        if (c.rowOffset >= 0) return rowsOffset + row * rowSize + c.rowOffset;
        return c.defaultOffset;
    }

    /** Integer value, or {@code fallback} when the column is missing or empty. */
    public long getLong(int row, String colName, long fallback) {
        Column c = column(colName);
        if (c == null) return fallback;
        int off = valueOffset(c, row);
        if (off < 0) return fallback;
        table.seek(off);
        switch (c.type) {
            case TYPE_U8: return table.u8();
            case TYPE_S8: return table.s8();
            case TYPE_U16: return table.u16();
            case TYPE_S16: return table.s16();
            case TYPE_U32: return table.u32();
            case TYPE_S32: return table.s32();
            case TYPE_U64: case TYPE_S64: return table.s64();
            case TYPE_F32: return (long) table.f32();
            default: return fallback;
        }
    }

    public String getString(int row, String colName) {
        Column c = column(colName);
        if (c == null || c.type != TYPE_STRING) return null;
        int off = valueOffset(c, row);
        if (off < 0) return null;
        table.seek(off);
        return safeString(stringPool + (int) table.u32());
    }

    /** Raw data column (type 0x0B): returns a copy of the referenced bytes, or null. */
    public byte[] getData(int row, String colName) {
        Column c = column(colName);
        if (c == null || c.type != TYPE_DATA) return null;
        int off = valueOffset(c, row);
        if (off < 0) return null;
        table.seek(off);
        int dataOff = (int) table.u32();
        int size = (int) table.u32();
        if (size <= 0) return null;
        table.seek(dataPool + dataOff);
        return table.bytes(size);
    }
}
