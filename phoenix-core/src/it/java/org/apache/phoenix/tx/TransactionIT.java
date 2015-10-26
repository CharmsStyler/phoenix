/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.apache.phoenix.tx;

import static org.apache.phoenix.util.TestUtil.INDEX_DATA_SCHEMA;
import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.apache.phoenix.util.TestUtil.TRANSACTIONAL_DATA_TABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

import org.apache.phoenix.end2end.BaseHBaseManagedTimeIT;
import org.apache.phoenix.end2end.Shadower;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.TestUtil;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Maps;

public class TransactionIT extends BaseHBaseManagedTimeIT {
	
	private static final String FULL_TABLE_NAME = INDEX_DATA_SCHEMA + QueryConstants.NAME_SEPARATOR + TRANSACTIONAL_DATA_TABLE;
	
    @Before
    public void setUp() throws SQLException {
        ensureTableCreated(getUrl(), TRANSACTIONAL_DATA_TABLE);
    }
		
	@Test
	public void testReadOwnWrites() throws Exception {
		String selectSql = "SELECT * FROM "+FULL_TABLE_NAME;
		Connection conn = DriverManager.getConnection(getUrl());
		try {
			conn.setAutoCommit(false);
			ResultSet rs = conn.createStatement().executeQuery(selectSql);
	     	assertFalse(rs.next());
	     	
	        String upsert = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk) VALUES(?, ?, ?, ?, ?, ?)";
	        PreparedStatement stmt = conn.prepareStatement(upsert);
			// upsert two rows
			TestUtil.setRowKeyColumns(stmt, 1);
			stmt.execute();
			TestUtil.setRowKeyColumns(stmt, 2);
			stmt.execute();
	        
	        // verify rows can be read even though commit has not been called
			rs = conn.createStatement().executeQuery(selectSql);
			TestUtil.validateRowKeyColumns(rs, 1);
			TestUtil.validateRowKeyColumns(rs, 2);
	        assertFalse(rs.next());
	        
	        conn.commit();
	        
	        // verify rows can be read after commit
	        rs = conn.createStatement().executeQuery(selectSql);
	        TestUtil.validateRowKeyColumns(rs, 1);
	        TestUtil.validateRowKeyColumns(rs, 2);
	        assertFalse(rs.next());
		}
        finally {
        	conn.close();
        }
	}
	
    @Test
    public void testDelete() throws Exception {
        String selectSQL = "SELECT * FROM " + FULL_TABLE_NAME;
        Connection conn1 = DriverManager.getConnection(getUrl());
        Connection conn2 = DriverManager.getConnection(getUrl());
        try {
            conn1.setAutoCommit(false);
            ResultSet rs = conn1.createStatement().executeQuery(selectSQL);
            assertFalse(rs.next());
            
            String upsert = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk) VALUES(?, ?, ?, ?, ?, ?)";
            PreparedStatement stmt = conn1.prepareStatement(upsert);
            // upsert two rows
            TestUtil.setRowKeyColumns(stmt, 1);
            stmt.execute();
            conn1.commit();
            
            TestUtil.setRowKeyColumns(stmt, 2);
            stmt.execute();
            
            // verify rows can be read even though commit has not been called
            int rowsDeleted = conn1.createStatement().executeUpdate("DELETE FROM " + FULL_TABLE_NAME);
            assertEquals(2, rowsDeleted);
            
            // Delete and second upsert not committed yet, so there should be one row.
            rs = conn2.createStatement().executeQuery("SELECT count(*) FROM " + FULL_TABLE_NAME);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            
            conn1.commit();
            
            // verify rows are deleted after commit
            // FIXME: this is failing, I think because Tephra isn't handling deletes like we need it to
            // TODO: confirm this works once we get the patch from Gary.
            rs = conn1.createStatement().executeQuery(selectSQL);
            assertFalse(rs.next());
        }
        finally {
            conn1.close();
        }
    }
    
	@Test
	public void testAutoCommitQuerySingleTable() throws Exception {
		Connection conn = DriverManager.getConnection(getUrl());
		try {
			conn.setAutoCommit(true);
			// verify no rows returned
			ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + FULL_TABLE_NAME);
			assertFalse(rs.next());
		} finally {
			conn.close();
		}
	}
	
    @Test
    public void testAutoCommitQueryMultiTables() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl());
        try {
            conn.setAutoCommit(true);
            // verify no rows returned
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + FULL_TABLE_NAME + " a JOIN " + FULL_TABLE_NAME + " b ON (a.long_pk = b.int_pk)");
            assertFalse(rs.next());
        } finally {
            conn.close();
        }
    }
    
	@Test
	public void testColConflicts() throws Exception {
		Connection conn1 = DriverManager.getConnection(getUrl());
		Connection conn2 = DriverManager.getConnection(getUrl());
		try {
			conn1.setAutoCommit(false);
			conn2.setAutoCommit(false);
			String selectSql = "SELECT * FROM "+FULL_TABLE_NAME;
			conn1.setAutoCommit(false);
			ResultSet rs = conn1.createStatement().executeQuery(selectSql);
	     	assertFalse(rs.next());
			// upsert row using conn1
			String upsertSql = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, a.int_col1) VALUES(?, ?, ?, ?, ?, ?, ?)";
			PreparedStatement stmt = conn1.prepareStatement(upsertSql);
			TestUtil.setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 10);
	        stmt.execute();
	        // upsert row using conn2
 			stmt = conn2.prepareStatement(upsertSql);
 			TestUtil.setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 11);
	        stmt.execute();
 	        
 	        conn1.commit();
	        //second commit should fail
 	        try {
 	 	        conn2.commit();
 	 	        fail();
 	        }	
 	        catch (SQLException e) {
 	        	assertEquals(e.getErrorCode(), SQLExceptionCode.TRANSACTION_CONFLICT_EXCEPTION.getErrorCode());
 	        }
		}
        finally {
        	conn1.close();
        }
	}
	
	private void testRowConflicts() throws Exception {
		Connection conn1 = DriverManager.getConnection(getUrl());
		Connection conn2 = DriverManager.getConnection(getUrl());
		try {
			conn1.setAutoCommit(false);
			conn2.setAutoCommit(false);
			String selectSql = "SELECT * FROM "+FULL_TABLE_NAME;
			conn1.setAutoCommit(false);
			ResultSet rs = conn1.createStatement().executeQuery(selectSql);
			boolean immutableRows = conn1.unwrap(PhoenixConnection.class).getTable(new PTableKey(null, FULL_TABLE_NAME)).isImmutableRows();
	     	assertFalse(rs.next());
			// upsert row using conn1
			String upsertSql = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, a.int_col1) VALUES(?, ?, ?, ?, ?, ?, ?)";
			PreparedStatement stmt = conn1.prepareStatement(upsertSql);
			TestUtil.setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 10);
	        stmt.execute();
	        // upsert row using conn2
	        upsertSql = "UPSERT INTO " + FULL_TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk, b.int_col2) VALUES(?, ?, ?, ?, ?, ?, ?)";
 			stmt = conn2.prepareStatement(upsertSql);
 			TestUtil.setRowKeyColumns(stmt, 1);
			stmt.setInt(7, 11);
 	        stmt.execute();
 	        
 	        conn1.commit();
	        //second commit should fail
 	        try {
 	 	        conn2.commit();
 	 	        if (!immutableRows) fail();
 	        }	
 	        catch (SQLException e) {
 	        	if (immutableRows) fail();
 	        	assertEquals(e.getErrorCode(), SQLExceptionCode.TRANSACTION_CONFLICT_EXCEPTION.getErrorCode());
 	        }
		}
        finally {
        	conn1.close();
        	conn2.close();
        }
	}
	
	@Test
	public void testRowConflictDetected() throws Exception {
		testRowConflicts();
	}
	
	@Test
	public void testNoConflictDetectionForImmutableRows() throws Exception {
		Connection conn = DriverManager.getConnection(getUrl());
		conn.createStatement().execute("ALTER TABLE " + FULL_TABLE_NAME + " SET IMMUTABLE_ROWS=true");
		testRowConflicts();
	}
	
}
