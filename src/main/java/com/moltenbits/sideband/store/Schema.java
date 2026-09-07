package com.moltenbits.sideband.store;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.SQLDataType.BIGINT;
import static org.jooq.impl.SQLDataType.BOOLEAN;
import static org.jooq.impl.SQLDataType.CLOB;
import static org.jooq.impl.SQLDataType.VARCHAR;

/**
 * The tables and columns of the store, declared once so every query is typed. The DDL that
 * creates them is {@code schema.sql}; the two must agree.
 */
final class Schema {

    /** {@code PRAGMA user_version} after the schema in {@code schema.sql} is installed. */
    static final int VERSION = 1;

    static final Table<Record> ENTRIES = table(name("entries"));
    /** The physical order: SQLite's rowid, assigned on insert and never reused since nothing is deleted. */
    static final Field<Long> SEQ = field(name("seq"), BIGINT);
    static final Field<String> ID = field(name("id"), VARCHAR);
    static final Field<String> CREATED_AT = field(name("created_at"), VARCHAR);
    static final Field<String> SENDER = field(name("sender"), VARCHAR);
    static final Field<String> VIA = field(name("via"), VARCHAR);
    /** The recipients in order, comma-separated; participant identifiers never contain a comma. */
    static final Field<String> RECIPIENTS = field(name("recipients"), VARCHAR);
    static final Field<String> TYPE = field(name("type"), VARCHAR);
    static final Field<String> ROUTE = field(name("route"), VARCHAR);
    static final Field<String> REPLY_TO = field(name("reply_to"), VARCHAR);
    static final Field<String> CAUSED_BY = field(name("caused_by"), VARCHAR);
    static final Field<Boolean> EXPECTS_REPLY = field(name("expects_reply"), BOOLEAN);
    static final Field<String> LIVE = field(name("live"), VARCHAR);
    static final Field<String> BACKLOG = field(name("backlog"), VARCHAR);
    static final Field<String> BODY = field(name("body"), CLOB);

    static final Table<Record> SESSIONS = table(name("sessions"));
    static final Field<String> ROLE = field(name("role"), VARCHAR);
    static final Field<String> SESSION_ID = field(name("session_id"), VARCHAR);
    static final Field<String> STARTED_AT = field(name("started_at"), VARCHAR);
    static final Field<Long> WATERMARK = field(name("watermark"), BIGINT);
    static final Field<Long> BOOKMARK = field(name("bookmark"), BIGINT);
    static final Field<Boolean> RESUMED = field(name("resumed"), BOOLEAN);

    private Schema() {
    }
}
