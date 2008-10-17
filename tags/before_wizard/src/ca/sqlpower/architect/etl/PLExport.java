package ca.sqlpower.architect.etl;

import java.sql.*;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;

import java.util.Set;

import ca.sqlpower.sql.*;
import ca.sqlpower.security.*;
import ca.sqlpower.architect.*;
import ca.sqlpower.architect.swingui.Monitorable;
import org.apache.log4j.Logger;

public class PLExport implements Monitorable {


	private static final Logger logger = Logger.getLogger(PLExport.class);

	protected LogWriter logWriter = null;
	
	public static final String PL_GENERATOR_VERSION
		= "PLExport $Revision$".replace('$',' ').trim();

	protected DefaultParameters defParam;
	protected String folderName;   // = "Architect Jobs";
	protected String jobId;
	protected String jobDescription;
	protected String jobComment;
	protected boolean runPLEngine;
	protected PLSecurityManager sm;

	protected ArchitectDataSource repositoryDataSource;
	protected ArchitectDataSource targetDataSource; //
	
	protected String targetSchema; // save this to properties file?
	protected String repositorySchema; //
			
	protected boolean finished; // so the Timer thread knows when to kill itself
	protected boolean cancelled; // FIXME: placeholder for when the user cancels halfway through a PL Export 			 
	SQLDatabase currentDB; // if this is non-null, an export job is running
	int tableCount = 0; // only has meaning when an export job is running
	
	public Integer getJobSize() throws ArchitectException {			
		if (currentDB != null) {
			return new Integer(currentDB.getChildren().size());
		} else {			
			return null;
		}		
	}
	
	public int getProgress() throws ArchitectException {
		if (currentDB != null) {
			return tableCount;
		} else {
			return 0;
		}
	}
	
	public boolean isFinished() throws ArchitectException {
		return finished;
	}

	public String getMessage() {
		return null;	
	}
	
	public boolean isCancelled() {
		return cancelled;
	}
	
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	public void cancelJob() {
		finished = true;
		cancelled = true;
	}
			
	/**
	 * Creates a folder if one with the name folderName does not exist
	 * already.
	 */
	public void maybeInsertFolder(Connection con) throws SQLException {
		Statement stmt = null;
		ResultSet rs = null;
		try {
			stmt = con.createStatement();
			rs = stmt.executeQuery("SELECT 1 FROM pl_folder WHERE folder_name="
								   +SQL.quote(folderName));
			if (!rs.next()) {
				StringBuffer sql = new StringBuffer("INSERT INTO PL_FOLDER (");
				sql.append("folder_name,folder_desc,folder_status,last_backup_no)");
				sql.append(" VALUES (");
				
				sql.append(SQL.quote(folderName));  // folder_name
				sql.append(",").append(SQL.quote("This Folder contains jobs and transactions created by the Power*Architect"));  // folder_desc
				sql.append(",").append(SQL.quote(null));  // folder_status
				sql.append(",").append(SQL.quote(null));  // last_backup_no
				sql.append(")");
				logWriter.info("Insert into PL FOLDER, PK=" + folderName);
				logger.debug("MAYBE INSERT SQL: " + sql.toString());
				stmt.executeUpdate(sql.toString());
			}
		} finally {
			if (rs != null) rs.close();
			if (stmt != null) stmt.close();
		}
	}
	
	/**
	 * Inserts an entry in the folderName folder of the named object
	 * of the given type.
	 */
	public void insertFolderDetail(Connection con, String objectType, String objectName)
		throws SQLException {
		StringBuffer sql = new StringBuffer("INSERT INTO PL_FOLDER_DETAIL (");
		sql.append("folder_name,object_type,object_name)");
		sql.append(" VALUES (");

		sql.append(SQL.quote(folderName));  // folder_name
		sql.append(",").append(SQL.quote(objectType));  // object_type
		sql.append(",").append(SQL.quote(objectName));  // object_name
		sql.append(")");
		logWriter.info("Insert into PL FOLDER_DETAIL, PK=" + folderName + "|" + objectType + "|" + objectName);
		Statement s = con.createStatement();
		try {
			logger.debug("INSERT FOLDER DETAIL SQL: " + sql.toString());
			s.executeUpdate(sql.toString());
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}

	/**
	 * Detects collisions in the trans_id and job_ib namespaces and generates
	 * the index of the next unique identifier.  The insert trans logic uses
	 * the index of the identifier, so return it instead of the unique string.
	 *
	 * @param con A connection to the PL database
	 */
	public int generateUniqueTransIdx(Connection con, String transId) throws SQLException {
		StringBuffer sql = new StringBuffer("SELECT TRANS_ID FROM TRANS WHERE TRANS_ID LIKE ");
		sql.append(SQL.quote(transId+"%"));
		Statement s = con.createStatement();
		ResultSet rs = null;
		Set set = new HashSet();
		try {
			logger.debug("DETECT TRANS_ID COLLISION: " + sql.toString());
			rs = s.executeQuery(sql.toString());
			while (rs.next()) {
				set.add(rs.getString(1));
			}
		} finally {
			if (rs != null) {
				rs.close();				
			}
			if (s != null) {
				s.close();
			}
		}
		boolean foundUnique = false;
		int i = 0;
		while (!foundUnique) {
			i++;
			foundUnique = !set.contains(transId + "_" + i);
			if (!foundUnique) {
				logger.debug("detected collision for trans id: " + transId + "_" + i);
			} else {
				logger.debug("next unique trans id is: " + transId + "_" + i);
			}
		}
		return i;
	}




	/**
	 * Inserts a job into the PL_JOB table.  The job name is specified by {@link #jobId}.
	 *
	 * @param con A connection to the PL database
	 */
	public void insertJob(Connection con) throws SQLException {
		
		StringBuffer sql = new StringBuffer("INSERT INTO PL_JOB (");
		sql.append("JOB_ID, JOB_DESC, JOB_FREQ_DESC, PROCESS_CNT, SHOW_PROGRESS_FREQ, PROCESS_SEQ_CODE, MAX_RETRY_COUNT, WRITE_DB_ERRORS_IND, ROLLBACK_SEGMENT_NAME, LOG_FILE_NAME, ERR_FILE_NAME, UNIX_LOG_FILE_NAME, UNIX_ERR_FILE_NAME, APPEND_TO_LOG_IND, APPEND_TO_ERR_IND, DEBUG_MODE_IND, COMMIT_FREQ, JOB_COMMENT, CREATE_DATE, LAST_update_DATE, LAST_update_USER, BATCH_SCRIPT_FILE_NAME, JOB_SCRIPT_FILE_NAME, UNIX_BATCH_SCRIPT_FILE_NAME, UNIX_JOB_SCRIPT_FILE_NAME, JOB_STATUS, LAST_BACKUP_NO, LAST_RUN_DATE, SKIP_PACKAGES_IND, SEND_EMAIL_IND, LAST_update_OS_USER, STATS_IND, checked_out_ind, checked_out_date, checked_out_user, checked_out_os_user");
		sql.append(") VALUES (");
		sql.append(SQL.quote(jobId));  // JOB_ID
		sql.append(",").append(SQL.quote(jobDescription));  // JOB_DESC
		sql.append(",").append(SQL.quote(null));  // JOB_FREQ_DESC
		sql.append(",").append(SQL.quote(null));  // PROCESS_CNT
		sql.append(",").append(SQL.quote(null));  // SHOW_PROGRESS_FREQ
		sql.append(",").append(SQL.quote(null));  // PROCESS_SEQ_CODE
		sql.append(",").append(SQL.quote(null));  // MAX_RETRY_COUNT
		sql.append(",").append(SQL.quote(null));  // WRITE_DB_ERRORS_IND
		sql.append(",").append(SQL.quote(null));  // ROLLBACK_SEGMENT_NAME
		logger.debug("default log path is: " + defParam.get("default_log_file_path"));
		logger.debug("default err path is: " + defParam.get("default_err_file_path"));
		sql.append(",").append(SQL.quote(escapeString(con,fixWindowsPath(defParam.get("default_log_file_path")))+jobId+".log"));  // LOG_FILE_NAME
		sql.append(",").append(SQL.quote(escapeString(con,fixWindowsPath(defParam.get("default_err_file_path")))+jobId+".err"));  // ERR_FILE_NAME
		sql.append(",").append(SQL.quote(fixUnixPath(defParam.get("default_log_file_path"))+jobId+".log"));  // UNIX_LOG_FILE_NAME
		sql.append(",").append(SQL.quote(fixUnixPath(defParam.get("default_err_file_path"))+jobId+".err"));  // UNIX_ERR_FILE_NAME
		sql.append(",").append(SQL.quote("N"));  // APPEND_TO_LOG_IND
		sql.append(",").append(SQL.quote("N"));  // APPEND_TO_ERR_IND
		sql.append(",").append(SQL.quote("N"));  // DEBUG_MODE_IND
		sql.append(",").append("100"); 			 // COMMIT_FREQ
		sql.append(",").append(SQL.quote(jobComment));  // JOB_COMMENT
		sql.append(",").append(SQL.escapeDate(con, new java.util.Date()));  // CREATE_DATE
		sql.append(",").append(SQL.escapeDate(con, new java.util.Date()));  // LAST_update_DATE
		sql.append(",").append(SQL.quote(con.getMetaData().getUserName()));  // LAST_update_USER
		sql.append(",").append(SQL.quote(null));  // BATCH_SCRIPT_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // JOB_SCRIPT_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // UNIX_BATCH_SCRIPT_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // UNIX_JOB_SCRIPT_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // JOB_STATUS
		sql.append(",").append(SQL.quote(null));  // LAST_BACKUP_NO
		sql.append(",").append(SQL.quote(null));  // LAST_RUN_DATE
		sql.append(",").append(SQL.quote(null));  // SKIP_PACKAGES_IND
		sql.append(",").append(SQL.quote("N"));  // SEND_EMAIL_IND
		sql.append(",").append(SQL.quote(System.getProperty("user.name")));  // LAST_update_OS_USER
		sql.append(",").append(SQL.quote("N"));  // STATS_IND
		sql.append(",").append(SQL.quote(null));  // checked_out_ind
		sql.append(",").append(SQL.quote(null));  // checked_out_date
		sql.append(",").append(SQL.quote(null));  // checked_out_user
		sql.append(",").append(SQL.quote(null));  // checked_out_os_user
		sql.append(")");
		Statement s = con.createStatement();
		logWriter.info("INSERT into PL_JOB, PK=" + jobId);
		
		try {
			logger.debug("INSERT PL_JOB: " + sql.toString());
			s.executeUpdate(sql.toString());
		} finally {
			if (s != null) {
				s.close();
			}
		}
		
	}			

	/**
	 * Inserts a job entry into the JOB_DETAIL table.  The job name is
	 * specified by {@link #jobId}.
	 *
	 * @param con A connection to the PL database
	 */
	public void insertJobDetail(Connection con, int seqNo, String objectType, String objectName) throws SQLException {
	
		StringBuffer sql= new StringBuffer("INSERT INTO JOB_DETAIL (");
		sql.append("JOB_ID, JOB_PROCESS_SEQ_NO, OBJECT_TYPE, OBJECT_NAME, JOB_DETAIL_COMMENT, LAST_update_DATE, LAST_update_USER, FAILURE_ABORT_IND, WARNING_ABORT_IND, PKG_PARAM, ACTIVE_IND, LAST_update_OS_USER )");
		sql.append(" VALUES (");
		sql.append(SQL.quote(jobId));  // JOB_ID
		sql.append(",").append(seqNo);  // JOB_PROCESS_SEQ_NO
		sql.append(",").append(SQL.quote(objectType));  // OBJECT_TYPE
		sql.append(",").append(SQL.quote(objectName));  // OBJECT_NAME
		sql.append(",").append(SQL.quote("Generated by POWER*Architect "+PL_GENERATOR_VERSION));  // JOB_DETAIL_COMMENT
		sql.append(",").append(SQL.escapeDate(con, new java.util.Date()));  // LAST_update_DATE
		sql.append(",").append(SQL.quote(con.getMetaData().getUserName()));  // LAST_update_USER
		sql.append(",").append(SQL.quote("N"));  // FAILURE_ABORT_IND
		sql.append(",").append(SQL.quote("N"));  // WARNING_ABORT_IND
		sql.append(",").append(SQL.quote(null));  // PKG_PARAM
		sql.append(",").append(SQL.quote("Y"));  // ACTIVE_IND
		sql.append(",").append(SQL.quote(System.getProperty("user.name")));  // LAST_update_OS_USER
		sql.append(")");
		logWriter.info("INSERT into JOB_DETAIL, PK=" + jobId + "|" + seqNo);
		Statement s = con.createStatement();
		try {
			logger.debug("INSERT JOB_DETAIL: " + sql.toString());
			s.executeUpdate(sql.toString());
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}

	/**
	 * Inserts a Power*Loader transaction header into the TRANS
	 * table.
	 *
	 * @param con A connection to the PL database
	 * @param transId the name that the new transaction should have.
	 * @param remarks The transaction comment/remarks.
	 * transaction will populate.
	 */
	public void insertTrans(Connection con, String transId, String remarks) throws ArchitectException, SQLException {
		StringBuffer sql = new StringBuffer();
		sql.append(" INSERT INTO TRANS (\n");
		sql.append(" TRANS_ID, TRANS_DESC, TRANS_COMMENT, ACTION_TYPE, MAX_RETRY_COUNT,\n");
		sql.append(" PROCESS_SEQ_CODE, LAST_update_DATE, LAST_update_USER, DEBUG_MODE_IND,\n");
		sql.append(" COMMIT_FREQ, PROCESS_ADD_IND, PROCESS_UPD_IND, PROCESS_DEL_IND,\n");
		sql.append(" WRITE_DB_ERRORS_IND, ROLLBACK_SEGMENT_NAME, ERR_FILE_NAME,\n");
		sql.append(" LOG_FILE_NAME, BAD_FILE_NAME, SHOW_PROGRESS_FREQ, SKIP_CNT,\n");
		sql.append(" PROCESS_CNT, CREATE_DATE, UNIX_LOG_FILE_NAME,\n");
		sql.append(" UNIX_ERR_FILE_NAME, UNIX_BAD_FILE_NAME, REMOTE_CONNECTION_STRING,\n");
		sql.append(" APPEND_TO_LOG_IND, APPEND_TO_ERR_IND, APPEND_TO_BAD_IND, TRANS_STATUS,\n");
		sql.append(" LAST_BACKUP_NO, LAST_RUN_DATE, SKIP_PACKAGES_IND, SEND_EMAIL_IND,\n");
		sql.append(" PROMPT_COLMAP_INDEXES_IND, TRANSACTION_TYPE, DELTA_SORT_IND,\n");
		sql.append(" LAST_update_OS_USER, STATS_IND, ODBC_IND, checked_out_ind,\n");
		sql.append(" checked_out_date, checked_out_user, checked_out_os_user\n");
		sql.append(") VALUES (");
		sql.append(SQL.quote(transId));  // TRANS_ID
		sql.append(",\n").append(SQL.quote("Generated by Power*Architect "+PL_GENERATOR_VERSION)); // TRANS_DESC
		sql.append(",\n").append(SQL.quote(remarks)); //TRANS_COMMENT
		sql.append(",\n").append(SQL.quote(null)); //ACTION_TYPE
		sql.append(",\n").append(SQL.quote(null)); //MAX_RETRY_COUNT
		sql.append(",\n").append(SQL.quote(null)); //PROCESS_SEQ_CODE
		sql.append(",\n").append(SQL.escapeDate(con, new java.util.Date())); //LAST_update_DATE
		sql.append(",\n").append(SQL.quote(con.getMetaData().getUserName())); //LAST_update_USER
		sql.append(",\n").append(SQL.quote("N")); //DEBUG_MODE_IND
		sql.append(",\n").append(defParam.get("commit_freq")); //COMMIT_FREQ
		sql.append(",\n").append(SQL.quote(null)); //PROCESS_ADD_IND 
		sql.append(",\n").append(SQL.quote(null)); //PROCESS_UPD_IND
		sql.append(",\n").append(SQL.quote(null)); //PROCESS_DEL_IND
		sql.append(",\n").append(SQL.quote(null)); //WRITE_DB_ERRORS_IND
		sql.append(",\n").append(SQL.quote(null)); //ROLLBACK_SEGMENT_NAME
		logger.debug("err_file_path: " + defParam.get("default_err_file_path"));
		logger.debug("log_file_path: " + defParam.get("default_log_file_path"));
		logger.debug("bad_file_path: " + defParam.get("default_bad_file_path"));
		sql.append(",\n").append(SQL.quote(escapeString(con,fixWindowsPath(defParam.get("default_err_file_path")))+transId+".err"));
		sql.append(",\n").append(SQL.quote(escapeString(con,fixWindowsPath(defParam.get("default_log_file_path")))+transId+".log"));
		sql.append(",\n").append(SQL.quote(escapeString(con,fixWindowsPath(defParam.get("default_bad_file_path")))+transId+".bad"));
		sql.append(",\n").append(defParam.get("show_progress_freq")); //SHOW_PROGRESS_FREQ
		sql.append(",\n").append("0");// SKIP_CNT
		sql.append(",\n").append("0");// PROCESS_CNT
		// SOURCE_DATE_FORMAT: col was missing in arthur-test-pl,
		// and we were setting it to null here, so I took it out of the statement. -JF
		sql.append(",\n").append(SQL.escapeDate(con, new java.util.Date())); //CREATE_DATE		
		sql.append(",\n").append(SQL.quote(fixUnixPath(defParam.get("default_unix_log_file_path"))+transId+".log"));
		sql.append(",\n").append(SQL.quote(fixUnixPath(defParam.get("default_unix_err_file_path"))+transId+".err"));
		sql.append(",\n").append(SQL.quote(fixUnixPath(defParam.get("default_unix_bad_file_path"))+transId+".bad"));
		sql.append(",\n").append(SQL.quote(null)); //REMOTE_CONNECTION_STRING
		sql.append(",\n").append(SQL.quote(defParam.get("append_to_log_ind"))); // APPEND_TO_LOG_IND
		sql.append(",\n").append(SQL.quote(defParam.get("append_to_err_ind"))); // APPEND_TO_ERR_IND
		sql.append(",\n").append(SQL.quote(defParam.get("append_to_bad_ind"))); // APPEND_TO_BAD_IND
		// REC_DELIMITER: col was missing in most of our schemas;
		// to save trouble, we'll let it default to null. -JF
		sql.append(",\n").append(SQL.quote(null)); // TRANS_STATUS
		sql.append(",\n").append(SQL.quote(null)); // LAST_BACKUP_NO
		sql.append(",\n").append(SQL.quote(null)); // LAST_RUN_DATE
		sql.append(",\n").append(SQL.quote("N")); // SKIP_PACKAGES_IND
		sql.append(",\n").append(SQL.quote("N")); // SEND_EMAIL_IND
		sql.append(",\n").append(SQL.quote(null)); // PROMPT_COLMAP_INDEXES_IND
		sql.append(",\n").append(SQL.quote("POWER_LOADER")); // TRANSACTION_TYPE
		sql.append(",\n").append(SQL.quote("Y")); // DELTA_SORT_IND
		sql.append(",\n").append(SQL.quote(System.getProperty("user.name"))); // LAST_update_OS_USER
		sql.append(",\n").append(SQL.quote("N")); // STATS_IND
		sql.append(",\n").append(SQL.quote("Y")); // ODBC_IND
		sql.append(",\n").append(SQL.quote(null)); // checked_out_ind
		sql.append(",\n").append(SQL.quote(null)); // checked_out_date
		sql.append(",\n").append(SQL.quote(null)); // checked_out_user
		sql.append(",\n").append(SQL.quote(null)); // checked_out_os_user
		sql.append(")");
		logWriter.info("INSERT into TRANS, PK=" + transId);
		Statement s = con.createStatement();
		try {			
			logger.debug("INSERT TRANS: " + sql.toString());
			s.executeUpdate(sql.toString());
		} catch (SQLException ex) {
			logger.error("This statement caused an exception: "+sql);
			throw ex;
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}

	/**
	 * Inserts a record into the TRANS_TABLE_FILE table.
	 *
	 * @param con The connection to the PL databse.
	 * @param transId The name of the header transaction.
	 * @param table The SQLTable that this record describes.
	 * @param isOutput True if table is an output table (part of the
	 * play pen); false if table is an input table (part of a source
	 * DB).
	 * @param seqNo The sequence number of this table in its parent
	 * transaction.
	 */
	public void insertTransTableFile(Connection con,
									 String transId,
									 String tableFileId,
									 SQLTable table,
									 boolean isOutput,
									 int seqNo) throws SQLException {
		StringBuffer sql= new StringBuffer("INSERT INTO TRANS_TABLE_FILE (");
		sql.append("TRANS_ID, TABLE_FILE_ID, TABLE_FILE_IND, TABLE_FILE_TYPE, INPUT_OUTPUT_IND, SYSTEM_NAME, SERVER_NAME, FILE_CHAR_SET, TEXT_DELIMITER, TEXT_QUALIFIER, OWNER, TABLE_FILE_NAME, TABLE_FILE_ACCESS_PATH, MAX_ADD_COUNT, MAX_UPD_COUNT, MAX_DEL_COUNT, MAX_ERR_COUNT, FILTER_CRITERION, PROC_SEQ_NO, HEADER_REC_IND, LAST_UPDATE_DATE, LAST_UPDATE_USER, TRANS_TABLE_FILE_COMMENT, DB_CONNECT_NAME, UNIX_FILE_ACCESS_PATH, REC_DELIMITER, SELECT_CLAUSE, FROM_CLAUSE, WHERE_CLAUSE, ORDER_BY_CRITERION, TRUNCATE_IND, ACTION_TYPE, ANALYZE_IND, PRE_PROCESSED_FILE_NAME, UNIX_PRE_PROCESSED_FILE_NAME, PARENT_FILE_ID, CHILD_REQUIRED_IND, LAST_UPDATE_OS_USER, DELETE_IND, FROM_CLAUSE_DB)");

		sql.append(" VALUES (");
		sql.append(SQL.quote(transId));  // TRANS_ID
		sql.append(",").append(SQL.quote(tableFileId));  // TABLE_FILE_ID
		sql.append(",").append(SQL.quote("TABLE"));  // TABLE_FILE_IND

		String type;
		String dbConnectName;
		ArchitectDataSource dataSource;
		
		if (isOutput) {
			dataSource = targetDataSource; // target table
		} else {
			dataSource = table.getParentDatabase().getDataSource(); // input table
		}		
		dbConnectName = dataSource.get(ArchitectDataSource.PL_LOGICAL);
		
		if (isOracle(dataSource)) {
			type = "ORACLE";
		} else if (isSQLServer(dataSource)) {
			type = "SQL SERVER";
		} else if (isDB2(dataSource)) {
			type = "DB2";
		} else if (isPostgres(dataSource)) {
			type = "POSTGRES";
		} else {
			throw new IllegalArgumentException("Unsupported target database type");
		}
		sql.append(",").append(SQL.quote(type));  // TABLE_FILE_TYPE

		sql.append(",").append(SQL.quote(isOutput ? "O" : "I"));  // INPUT_OUTPUT_IND
		sql.append(",").append(SQL.quote(null));  // SYSTEM_NAME
		sql.append(",").append(SQL.quote(null));  // SERVER_NAME
		sql.append(",").append(SQL.quote(null));  // FILE_CHAR_SET
		sql.append(",").append(SQL.quote(null));  // TEXT_DELIMITER
		sql.append(",").append(SQL.quote(null));  // TEXT_QUALIFIER
		sql.append(",").append(SQL.quote(isOutput ? targetSchema : table.getParent().toString()));  // OWNER
		sql.append(",").append(SQL.quote(table.getName()));  // TABLE_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // TABLE_FILE_ACCESS_PATH
		sql.append(",").append(SQL.quote(null));  // MAX_ADD_COUNT
		sql.append(",").append(SQL.quote(null));  // MAX_UPD_COUNT
		sql.append(",").append(SQL.quote(null));  // MAX_DEL_COUNT
		sql.append(",").append(SQL.quote(null));  // MAX_ERR_COUNT
		sql.append(",").append(SQL.quote(null));  // FILTER_CRITERION
		sql.append(",").append(seqNo);  // PROC_SEQ_NO
		sql.append(",").append(SQL.quote(null));  // HEADER_REC_IND
		sql.append(",").append(SQL.escapeDate(con, new java.util.Date()));  // LAST_update_DATE
		sql.append(",").append(SQL.quote(con.getMetaData().getUserName()));  // LAST_update_USER
		sql.append(",").append(SQL.quote("Generated by Power*Architect "+PL_GENERATOR_VERSION));  // TRANS_TABLE_FILE_COMMENT
		sql.append(",").append(SQL.quote(dbConnectName)); // DB_CONNECT_NAME (PL_LOGICAL)
		sql.append(",").append(SQL.quote(null));  // UNIX_FILE_ACCESS_PATH
		sql.append(",").append(SQL.quote(null));  // REC_DELIMITER
		sql.append(",").append(SQL.quote(null));  // SELECT_CLAUSE
		sql.append(",").append(SQL.quote(null));  // FROM_CLAUSE
		sql.append(",").append(SQL.quote(null));  // WHERE_CLAUSE
		sql.append(",").append(SQL.quote(null));  // ORDER_BY_CRITERION
		sql.append(",").append(SQL.quote(null));  // TRUNCATE_IND
		sql.append(",").append(SQL.quote(null));  // ACTION_TYPE
		sql.append(",").append(SQL.quote(null));  // ANALYZE_IND
		sql.append(",").append(SQL.quote(null));  // PRE_PROCESSED_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // UNIX_PRE_PROCESSED_FILE_NAME
		sql.append(",").append(SQL.quote(null));  // PARENT_FILE_ID
		sql.append(",").append(SQL.quote(null));  // CHILD_REQUIRED_IND
		sql.append(",").append(SQL.quote(System.getProperty("user.name")));  // LAST_UPDATE_OS_USER
		sql.append(",").append(SQL.quote(null));  // DELETE_IND
		sql.append(",").append(SQL.quote(null));  // FROM_CLAUSE_DB
		sql.append(")");
		logWriter.info("INSERT into TRANS_TABLE_FILE, PK=" + transId + "|" + tableFileId);
		Statement s = con.createStatement();
		try {
			logger.debug("INSERT TRANS_TABLE_FILE: " + sql.toString());
			s.executeUpdate(sql.toString());
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}
	
	/**
	 * Inserts mapping records (by calling insertTransColMap) for all
	 * mandatory columns of outputTable, as well as all columns of
	 * outputTable whose source table is inputTable.
	 */
	public void insertMappings(Connection con,
							   String transId,
							   String outputTableId,
							   SQLTable outputTable,
							   String inputTableId,
							   SQLTable inputTable) throws SQLException, ArchitectException {
		int seqNo = 1;
		Iterator outCols = outputTable.getColumns().iterator();
		while (outCols.hasNext()) {
			SQLColumn outCol = (SQLColumn) outCols.next();
			SQLColumn sourceCol = outCol.getSourceColumn();
			if (sourceCol != null) { 
				if ( (sourceCol.getParentTable() == inputTable) && 
				 ((outCol.getNullable() == DatabaseMetaData.columnNoNulls) || (sourceCol != null ) )) { 					
					 //	also covers PK					
					 insertTransColMap(con, transId, outputTableId, outCol, inputTableId, seqNo);
				     seqNo++;
			    }
			}	
		}
	}

	/**
	 * Inserts a column mapping record for outputColumn into the
	 * TRANS_COL_MAP table.
	 *
	 * @param con The connection to the PL database.
	 * @param transId The transaction name.
	 * @param outputColumn The column to generate a mapping for.
	 * @param seqNo The sequence number of the output table in trans_table_file
	 */
	public void insertTransColMap(Connection con,
								  String transId,
								  String outputTableId,
								  SQLColumn outputColumn,
								  String inputTableId,
								  int seqNo) throws SQLException {
		SQLColumn inputColumn = outputColumn.getSourceColumn();
		String inputColumnName;
		if (inputColumn != null) {
			inputColumnName = inputColumn.getName();
		} else {
			inputColumnName = null;
		}
		StringBuffer sql= new StringBuffer("INSERT INTO TRANS_COL_MAP (");
		sql.append("TRANS_ID, INPUT_TABLE_FILE_ID, INPUT_TRANS_COL_NAME, OUTPUT_TABLE_FILE_ID, OUTPUT_TRANS_COL_NAME, VALID_ACTION_TYPE, NATURAL_ID_IND, REAL_MEM_TRANS_IND, DEFAULT_VALUE, INPUT_TRANS_VALUE, OUTPUT_TRANS_VALUE, TRANS_TABLE_NAME, SEQ_NAME, GRP_FUNC_STRING, TRANS_COL_MAP_COMMENT, PROCESS_SEQ_NO, LAST_update_DATE, LAST_update_USER, OUTPUT_PROC_SEQ_NO, TRANSLATION_VALUE, ACTIVE_IND, PL_SEQ_IND, PL_SEQ_INCREMENT, LAST_update_OS_USER, TRANSFORMATION_CRITERIA, PL_SEQ_update_TABLE_IND, SEQ_TABLE_IND, SEQ_WHERE_CLAUSE)");
		sql.append(" VALUES (");

		sql.append(SQL.quote(transId));  // TRANS_ID
		sql.append(",").append(SQL.quote(inputTableId)); //INPUT_TABLE_FILE_ID
		sql.append(",").append(SQL.quote(inputColumnName));  // INPUT_TRANS_COL_NAME
		sql.append(",").append(SQL.quote(outputTableId));  // OUTPUT_TABLE_FILE_ID
		sql.append(",").append(SQL.quote(outputColumn.getName()));  // OUTPUT_TRANS_COL_NAME
		sql.append(",").append(SQL.quote(outputColumn.getPrimaryKeySeq() != null ? "A" : "AU"));  // VALID_ACTION_TYPE
		sql.append(",").append(SQL.quote(outputColumn.getPrimaryKeySeq() != null ? "Y" : "N"));  // NATURAL_ID_IND
		sql.append(",").append(SQL.quote(null));  // REAL_MEM_TRANS_IND
		sql.append(",").append(SQL.quote(outputColumn.getDefaultValue()));  // DEFAULT_VALUE
		sql.append(",").append(SQL.quote(null));  // INPUT_TRANS_VALUE
		sql.append(",").append(SQL.quote(null));  // OUTPUT_TRANS_VALUE
		sql.append(",").append(SQL.quote(null));  // TRANS_TABLE_NAME
		sql.append(",").append(SQL.quote(null));  // SEQ_NAME
		sql.append(",").append(SQL.quote(null));  // GRP_FUNC_STRING
		sql.append(",").append(SQL.quote("Generated by Power*Architect "+PL_GENERATOR_VERSION));  // TRANS_COL_MAP_COMMENT
		sql.append(",").append(SQL.quote(null));  // PROCESS_SEQ_NO
		sql.append(",").append(SQL.escapeDate(con, new java.util.Date()));  // LAST_update_DATE
		sql.append(",").append(SQL.quote(con.getMetaData().getUserName()));  // LAST_update_USER
		sql.append(",").append(seqNo);  // OUTPUT_PROC_SEQ_NO  from trans_table_file.seq_no?
		sql.append(",").append(SQL.quote(null));  // TRANSLATION_VALUE
		sql.append(",").append(SQL.quote("Y"));  // ACTIVE_IND
		sql.append(",").append(SQL.quote(null));  // PL_SEQ_IND
		sql.append(",").append(SQL.quote(null));  // PL_SEQ_INCREMENT
		sql.append(",").append(SQL.quote(System.getProperty("user.name")));  // LAST_update_OS_USER
		sql.append(",").append(SQL.quote(null));  // TRANSFORMATION_CRITERIA
		sql.append(",").append(SQL.quote(null));  // PL_SEQ_update_TABLE_IND
		sql.append(",").append(SQL.quote(null));  // SEQ_TABLE_IND
		sql.append(",").append(SQL.quote(null));  // SEQ_WHERE_CLAUSE
		sql.append(")");
		logWriter.info("INSERT into TRANS_COL_MAP, PK=" + transId + "|" + outputTableId + "|" + outputColumn.getName());		
		Statement s = con.createStatement();
		try {
			logger.debug("INSERT TRANS_COL_MAP: " + sql.toString());
			s.executeUpdate(sql.toString());
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}

	/**
	 * Inserts a transaction exception handler into the
	 * TRANS_EXCEPT_HANDLE table.  You specify the action type as one
	 * of ACTION_TYPE_ADD, ACTION_TYPE_UPDATE, or ACTION_TYPE_DELETE
	 * and this method figures out the rest for you.
	 *
	 * @param con A connection to the PL database
	 * @param actionType the action type to insert.
	 * @param transId the transaction to add this exception handler to.
	 */
	public void insertTransExceptHandler(Connection con, String actionType,  String transId, Connection targetConnection) throws SQLException {

		String errorCode = "";
		String resultActionType;
		String databaseType = "";

		if (DBConnection.isOracle(targetConnection)) {
			databaseType = "ORACLE";
			if(actionType.equals("A")) {
				errorCode = "-1";
				resultActionType="CHANGE_TO_UPD";
			} else if(actionType.equals("U")) {
				errorCode = "1403";
				resultActionType="CHANGE_TO_ADD";
			} else if(actionType.equals("D")) {
				errorCode = "1403";
				resultActionType="SKIP";
			} else {
				throw new IllegalArgumentException("Invalid Action type " + actionType); 
			}
		} else if (DBConnection.isSQLServer(targetConnection)) {
			databaseType = "SQL SERVER";
			if(actionType.equals("A")) {
				errorCode = "-2627";
				resultActionType="CHANGE_TO_UPD";
			} else if(actionType.equals("U")) {
				errorCode = "100";
				resultActionType="CHANGE_TO_ADD";
			} else if(actionType.equals("D")) {
				errorCode = "100";
				resultActionType="SKIP";
			} else {
				throw new IllegalArgumentException("Invalid Action type " + actionType); 
			}
		} else if (DBConnection.isDB2(targetConnection)) {
			databaseType = "DB2";
			if(actionType.equals("A")) {
				errorCode = "-803";
				resultActionType="CHANGE_TO_UPD";
			} else if(actionType.equals("U")) {
				errorCode = "100";
				resultActionType="CHANGE_TO_ADD";
			} else if(actionType.equals("D")) {
				errorCode = "100";
				resultActionType="SKIP";
			} else {
				throw new IllegalArgumentException("Invalid Action type " + actionType); 
			}
		} else if (DBConnection.isPostgres(targetConnection)) {
			databaseType = "POSTGRES";
			if(actionType.equals("A")) {
				errorCode = "23505";
				resultActionType="CHANGE_TO_UPD";
			} else if(actionType.equals("U")) {
				errorCode = "100";
				resultActionType="CHANGE_TO_ADD";
			} else if(actionType.equals("D")) {
				errorCode = "100";
				resultActionType="SKIP";
			} else {
				throw new IllegalArgumentException("Invalid Action type " + actionType); 
			}
		} else {
			throw new IllegalArgumentException("Unsupported Target Database type");
		}
		StringBuffer sql= new StringBuffer("INSERT INTO TRANS_EXCEPT_HANDLE (");
		sql.append("TRANS_ID,INPUT_ACTION_TYPE,DBMS_ERROR_CODE,RESULT_ACTION_TYPE,EXCEPT_HANDLE_COMMENT,LAST_update_DATE,LAST_update_USER,PKG_NAME,PKG_PARAM,PROC_FUNC_IND,ACTIVE_IND,LAST_update_OS_USER,DATABASE_TYPE)");
	    sql.append(" VALUES (");
		sql.append(SQL.quote(transId));	// TRANS_ID
		sql.append(",").append(SQL.quote(actionType));	// INPUT_ACTION_TYPE
		sql.append(",").append(SQL.quote(errorCode));	// DBMS_ERROR_CODE
		sql.append(",").append(SQL.quote(resultActionType));	// RESULT_ACTION_TYPE
		sql.append(",").append(SQL.quote("Generated by Power*Architect "+PL_GENERATOR_VERSION));	//EXCEPT_HANDLE_COMMENT
		sql.append(",").append(SQL.escapeDate(con, new java.util.Date()));  // LAST_update_DATE
		sql.append(",").append(SQL.quote(con.getMetaData().getUserName()));  // LAST_update_USER
		sql.append(",").append(SQL.quote(null));	// PKG_NAME
		sql.append(",").append(SQL.quote(null));	// PKG_PARAM
		sql.append(",").append(SQL.quote(null));    // PROC_FUNC_IND
		sql.append(",").append(SQL.quote("Y"));     // ACTIVE_IND
		sql.append(",").append(SQL.quote(System.getProperty("user.name"))); // LAST_update_OS_USER
		sql.append(",").append(SQL.quote(databaseType)); // DATABASE_TYPE
		sql.append(")");
		logWriter.info("INSERT into TRANS_EXCEPT_HANDLE, PK=" + transId + "|" + actionType + "|" + errorCode);		
		Statement s = con.createStatement();
		try {
			logger.debug("INSERT TRANS_EXCEPT_HANDLE: " + sql.toString());
			s.executeUpdate(sql.toString());
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}

	/**
	 * Does the actual insertion of the PL metadata records into the PL database.
     * 
     * @return
     * 
     * TODO: Strictly speaking, this method should be synchronized (though currently, it's
     * pretty hard to get two copies of it going at the same time)
	 */
	public void export(SQLDatabase db) throws SQLException, ArchitectException {
		finished = false;
		try {
			// first, set the logWriter
			logWriter = new LogWriter(ArchitectSession.getInstance().getUserSettings().getETLUserSettings().getETLLogPath());			
			
			currentDB = db;
			
			SQLDatabase repository = new SQLDatabase(repositoryDataSource); // we are exporting db into this
			Connection con = null;
        	
			con = repository.getConnection();
			try {
				defParam = new DefaultParameters(con);
			} catch (PLSchemaException p) {
				throw new ArchitectException("couldn't load default parameters", p);
			}

			SQLDatabase target = new SQLDatabase(targetDataSource);
			Connection tCon = target.getConnection();
			
			sm = null;
			for (int tryNum = 0; tryNum < 3 && sm == null; tryNum++) {
			    String username;
			    if (tryNum == 1) {
			        username = repositoryDataSource.get(ArchitectDataSource.PL_UID).toUpperCase();
			    } else if (tryNum == 2) {
			        username = repositoryDataSource.get(ArchitectDataSource.PL_UID).toLowerCase();
			    } else {
			        username = repositoryDataSource.get(ArchitectDataSource.PL_UID);
			    }
			    try {
			        // don't need to verify passwords in client apps (as opposed to webapps)
			        sm = new PLSecurityManager(con, 
			                username,
			                repositoryDataSource.get(ArchitectDataSource.PL_PWD),
			                false);
			    } catch (PLSecurityException se) {
			        logger.debug("Couldn't find pl user "+username, se);
			    }
			}
			if (sm == null) {
		        throw new ArchitectException("Could not find login for: " 
		                + repositoryDataSource.get(ArchitectDataSource.PL_UID));
			}
			logWriter.info("Starting creation of job <" + jobId + "> at " + new java.util.Date(System.currentTimeMillis()));
			logWriter.info("Connected to database: " + repositoryDataSource.toString());
			maybeInsertFolder(con);			

			PLJob job = new PLJob(jobId);
			
			insertJob(con);
			insertFolderDetail(con, job.getObjectType(), job.getObjectName());
			
			// This will order the target tables so that the parent tables are loaded 
			// before their children
			DepthFirstSearch targetDFS = new DepthFirstSearch(db);
			List tables = targetDFS.getFinishOrder();
			
			if (logger.isDebugEnabled()) {
			    StringBuffer tableOrder = new StringBuffer();
			    Iterator dit = tables.iterator();
			    while (dit.hasNext()) {
			        tableOrder.append(((SQLTable) dit.next()).getName()).append(", ");
			    }
			    logger.debug("Safe load order for job is: "+tableOrder);
			}

/*			Left in for posterity so we can refer to how things used to be.  See below
 *          for the brave new world, in which we process columns 1 at a time, and 
 *          enforce a strict 1-to-1 mapping between transactions and inputs.
			
			int outputTableNum = 1;
			Iterator targetTableIt = tables.iterator();
			while (targetTableIt.hasNext()) {
				tableCount++;
				SQLTable outputTable = (SQLTable) targetTableIt.next();
				HashSet inputTables = new HashSet();
				Iterator cols = outputTable.getColumns().iterator();
				while (cols.hasNext()) {
					SQLColumn outputCol = (SQLColumn) cols.next();
					SQLColumn inputCol = outputCol.getSourceColumn();
					// looking for unique input tables of outputTable
					if (inputCol != null && !inputTables.contains(inputCol.getParentTable())) {
						SQLTable inputTable = inputCol.getParentTable();
						inputTables.add(inputTable);
						String baseTransName = PLUtils.toPLIdentifier("LOAD_"+outputTable.getName());
						int transNum = generateUniqueTransIdx(con,baseTransName);
						String transName = baseTransName + "_" + transNum;
						insertTrans(con, transName, outputTable.getRemarks());
						insertFolderDetail(con, "TRANSACTION", transName);
						insertTransExceptHandler(con, "A", transName, tCon); // error handling is w.r.t. target database
						insertTransExceptHandler(con, "U", transName, tCon); // error handling is w.r.t. target database
						insertTransExceptHandler(con, "D", transName, tCon); // error handling is w.r.t. target database
						insertJobDetail(con, outputTableNum*10, "TRANSACTION", transName);
						logger.debug("outputTableNum: " + outputTableNum);
						logger.debug("transName: " + transName);
						String outputTableId = PLUtils.toPLIdentifier(outputTable.getName()+"_OUT_"+outputTableNum);
						String inputTableId = PLUtils.toPLIdentifier(inputTable.getName()+"_IN_"+transNum);
						logger.debug("outputTableId: " + outputTableId);
						logger.debug("inputTableId: " + inputTableId);
						insertTransTableFile(con, transName, outputTableId, outputTable, true, transNum);						
						insertTransTableFile(con, transName, inputTableId, inputTable, false, transNum);
						insertMappings(con, transName, outputTableId, outputTable, inputTableId, inputTable);
					}
					outputTableNum++;
				}
			}
			
			*/

			int outputTableNum = 1;
			Hashtable inputTables = new Hashtable();
			
			Iterator targetTableIt = tables.iterator();
			while (targetTableIt.hasNext()) {
				tableCount++;
				SQLTable outputTable = (SQLTable) targetTableIt.next();
				// reset loop variables for each output table
				boolean createdOutputTableMetaData = false;
				int transNum = 0;
				int seqNum = 1; // borrowed from insertColumnMappings, not sure if this is significant...
				String transName = null;
				String outputTableId = null;
				String inputTableId = null;
				//
				Iterator cols = outputTable.getColumns().iterator();
				while (cols.hasNext()) {
					SQLColumn outputCol = (SQLColumn) cols.next();
					SQLColumn inputCol = outputCol.getSourceColumn();					
					if (inputCol != null && !inputTables.keySet().contains(inputCol.getParentTable())) {
						// create transaction and input table meta data here if we need to
						SQLTable inputTable = inputCol.getParentTable();
						String baseTransName = PLUtils.toPLIdentifier("LOAD_"+outputTable.getName());
						transNum = generateUniqueTransIdx(con,baseTransName);
						transName = baseTransName + "_" + transNum;
						logger.debug("transName: " + transName);
						insertTrans(con, transName, outputTable.getRemarks());
						insertFolderDetail(con, "TRANSACTION", transName);
						insertTransExceptHandler(con, "A", transName, tCon); // error handling is w.r.t. target database
						insertTransExceptHandler(con, "U", transName, tCon); // error handling is w.r.t. target database
						insertTransExceptHandler(con, "D", transName, tCon); // error handling is w.r.t. target database
						insertJobDetail(con, outputTableNum*10, "TRANSACTION", transName);
						inputTableId = PLUtils.toPLIdentifier(inputTable.getName()+"_IN_"+transNum);
						logger.debug("inputTableId: " + inputTableId);
						insertTransTableFile(con, transName, inputTableId, inputTable, false, transNum);
						inputTables.put(inputTable, new PLTransaction(transName,inputTableId,transNum));
					} else {
						// restore input/transaction variables
						PLTransaction plt = (PLTransaction) inputTables.get(inputCol.getParentTable());
						transName = plt.getName();
						inputTableId = plt.getInputTableId();
						transNum = plt.getTransNum();
					}
					
					if (!createdOutputTableMetaData) {
						// create output table meta data once
						logger.debug("outputTableNum: " + outputTableNum);
						outputTableId = PLUtils.toPLIdentifier(outputTable.getName()+"_OUT_"+outputTableNum);
						logger.debug("outputTableId: " + outputTableId);
						insertTransTableFile(con, transName, outputTableId, outputTable, true, transNum);						
						createdOutputTableMetaData = true;
					}			
					
					// finally, insert the mapping for this column
					if (inputCol != null)  { 					
						// note: output proc seq num appears to be meaningless based on what the Power Loader
						// does after you view generated transaction in the VB Front end.
						insertTransColMap(con, transName, outputTableId, outputCol, inputTableId, seqNum*10);
					}
					seqNum++;
				}
				outputTableNum++; // moved out of inner loop
			}
		
		
		} finally {
			finished = true;			
			currentDB = null;
			// close and flush the logWriter (and set the reference to null)
			logWriter.flush();		
			logWriter.close();
			logWriter=null;
		}
	}

	class PLTransaction {
		
		public PLTransaction (String name, String inputTableId, int transNum) {
			this.name = name;
			this.inputTableId = inputTableId;
			this.transNum = transNum;
		}
		
		private String name;
		private String inputTableId;
		private int transNum;
		
		public String getInputTableId() {
			return inputTableId;
		}

		public void setInputTableId(String inputTableId) {
			this.inputTableId = inputTableId;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getTransNum() {
			return transNum;
		}

		public void setTransNum(int transNum) {
			this.transNum = transNum;
		}
		
	}

	// --------------------------- UTILITY METHODS ------------------------
	protected String fixWindowsPath(String path) {
		if (path == null) {
			return "";
		}		
		if ( ! path.endsWith("\\")){
			path +="\\";
		}		

		return path;
	}

	protected String fixUnixPath(String path) {
		if (path == null) {
			path="";
		} else if ( ! path.endsWith("/")){
			path +="/";
		}
		return path;
	}

	/*
	 * Do any platform dependent escaping of Strings here.  For example,
	 * Postgres backslashes need to be doubled or Postgres will mangle them.
	 *
	 * FIXME: this needs to be pushed into the more generic SQL utility class
	 * in ca.sqlpower.sql.  All Strings must be washed through it.  And then 
	 * the entire application suite needs to be regression tested. 
	 * 
	 */
	protected String escapeString(Connection con, String string) {
		String retString = null;
		if (DBConnection.isPostgres(con)) {
			// compilation halves the number of slashes, and then regex
			// halves them once again.  Confusing eh?  4==1...
			retString = string.replaceAll("\\\\","\\\\\\\\");
		} else {
			retString = string;
		}
		return retString;			
	}

	protected boolean isOracle(ArchitectDataSource dbcs) {
		if(dbcs.getDriverClass().toLowerCase().indexOf("oracledriver") >= 0) {
			return true;
		} else {
			return false;
		}
	}

	protected boolean isSQLServer(ArchitectDataSource dbcs) {
		if(dbcs.getDriverClass().toLowerCase().indexOf("sqlserver") >= 0) {
			return true;
		} else {
			return false;
		}
	}

	protected boolean isDB2(ArchitectDataSource dbcs) {
		if(dbcs.getDriverClass().toLowerCase().indexOf("db2") >= 0) {
			return true;
		} else {
			return false;
		}
	}

	protected boolean isPostgres(ArchitectDataSource dbcs) {
		if(dbcs.getDriverClass().toLowerCase().indexOf("postgres") >= 0) {
			return true;
		} else {
			return false;
		}
	}

	public static class PLJob implements DatabaseObject {

		public String jobId;

		public PLJob(String jobId) {
			this.jobId = jobId;
		}

		public String getObjectType() {
			return "JOB";
		}

		public String getObjectName() {
			return jobId;
		}
	}

	public static class PLTrans implements DatabaseObject {

		public String transId;

		public PLTrans(String transId) {
			this.transId = transId;
		}

		public String getObjectType() {
			return "TRANSACTION";
		}

		public String getObjectName() {
			return transId;
		}
	}
	
	// ----------------------- accessors and mutators --------------------------

	public void setJobId(String jobId){
		this.jobId = PLUtils.toPLIdentifier(jobId);
	}
	
	public String getJobId() {
		return jobId;
	}

	public void setFolderName(String folderName){
		this.folderName = PLUtils.toPLIdentifier(folderName);
	}
	
	public String getFolderName() {
		return folderName;
	}

	public void setJobDescription(String jobDescription){
		this.jobDescription = jobDescription;
	}

	public String getJobDescription() {
		return jobDescription;
	}

	public void setJobComment(String jobComment){
		this.jobComment = jobComment;
	}

	public String getJobComment() {
		return jobComment;
	}

	public void setRepositoryDataSource(ArchitectDataSource dbcs){
		this.repositoryDataSource = dbcs;
	}

	public ArchitectDataSource getRepositoryDataSource() {
		return repositoryDataSource;
	}
			
	public void setTargetSchema(String schema) {
		targetSchema = schema;
	}
	
	public String getTargetSchema() {
		return targetSchema;
	}
	
	public boolean getRunPLEngine() {
		return runPLEngine;
	}
	
	public void setRunPLEngine(boolean runEngine) {
		runPLEngine = runEngine;
	}
	
	public void setPlSecurityManager(PLSecurityManager sm) {
		this.sm = sm;
	}

	public PLSecurityManager getPlSecurityManager() {
		return sm;
	}
	/**
	 * @return Returns the targetDataSource.
	 */
	public ArchitectDataSource getTargetDataSource() {
		return targetDataSource;
	}
	/**
	 * @param targetDataSource The targetDataSource to set.
	 */
	public void setTargetDataSource(ArchitectDataSource targetDataSource) {
		this.targetDataSource = targetDataSource;
	}
}