package com.knowstack.engine.document.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.fastjson2.JSON;
import com.knowstack.engine.document.constant.FileType;
import com.knowstack.engine.document.constant.KnowledgeBaseType;
import com.knowstack.engine.document.entity.KnowledgeDocument;
import com.knowstack.engine.document.entity.TableMeta;
import com.knowstack.engine.document.mapper.TableMetaMapper;
import com.knowstack.engine.document.service.FileProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 将 Excel 第一张工作表导入文档独占的 MySQL 表，并登记供后续查询使用的表头元数据。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelProcessServiceImpl implements FileProcessService {
    private static final int BATCH_SIZE = 200;
    private final TableMetaMapper tableMetaMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public String processDocument(KnowledgeDocument document, InputStream inputStream) {
        if (document.getDocId() == null || document.getCurrentVersionId() == null) {
            throw new IllegalArgumentException("导入 Excel 前必须保存文档和版本");
        }
        String tableName = "custom_data_query_" + document.getDocId();
        List<List<String>> rows = new ArrayList<>();
        try (inputStream) {
            if (document.getDocTitle().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
                try (CSVParser csv = CSVFormat.RFC4180.parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    csv.forEach(record -> {
                        List<String> row = new ArrayList<>(record.size());
                        record.forEach(row::add);
                        if (rows.isEmpty() && !row.isEmpty()) {
                            row.set(0, row.getFirst().replaceFirst("^\\uFEFF", ""));
                        }
                        rows.add(row);
                    });
                }
            } else {
                EasyExcel.read(inputStream, new ReadListener<Map<Integer, String>>() {
                    @Override
                    public void invoke(Map<Integer, String> data, AnalysisContext context) {
                        int width = data.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
                        List<String> row = new ArrayList<>(width);
                        for (int i = 0; i < width; i++) {
                            row.add(data.get(i));
                        }
                        rows.add(row);
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) { }
                }).headRowNumber(0).sheet(0).doRead();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("表格文件解析失败", e);
        }
        if (rows.size() < 2 || rows.getFirst().isEmpty()) {
            throw new IllegalArgumentException("表格必须包含表头和至少一行数据");
        }
        List<ColumnInfo> columns = columns(rows.getFirst());
        int width = columns.size();
        List<List<String>> data = rows.subList(1, rows.size());
        for (List<String> row : data) {
            if (row.size() > width) {
                throw new IllegalArgumentException("Excel 数据列数超过表头列数");
            }
        }
        // 每个文档一张表，避免不同上传的同名文件覆盖其他文档的数据。
        String createSql = "CREATE TABLE `" + tableName + "` (`id` BIGINT NOT NULL AUTO_INCREMENT, "
                + columns.stream().map(c -> "`" + c.columnName() + "` TEXT NULL")
                        .collect(Collectors.joining(", "))
                + ", PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        jdbcTemplate.execute(createSql);
        try {
            String insertSql = "INSERT INTO `" + tableName + "` ("
                    + columns.stream().map(c -> "`" + c.columnName() + "`").collect(Collectors.joining(", "))
                    + ") VALUES (" + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
            for (int start = 0; start < data.size(); start += BATCH_SIZE) {
                List<List<String>> batch = data.subList(start, Math.min(start + BATCH_SIZE, data.size()));
                jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int rowIndex) throws SQLException {
                        List<String> row = batch.get(rowIndex);
                        for (int col = 0; col < width; col++) {
                            String value = col < row.size() ? row.get(col) : null;
                            if (value == null || value.isEmpty()) {
                                ps.setNull(col + 1, Types.VARCHAR);
                            } else {
                                ps.setString(col + 1, value);
                            }
                        }
                    }

                    @Override
                    public int getBatchSize() {
                        return batch.size();
                    }
                });
            }
            TableMeta meta = new TableMeta();
            meta.setTableName(tableName);
            meta.setDescription(document.getDescription() == null ? "Excel: " + document.getDocTitle() : document.getDescription());
            meta.setCreateSql(createSql);
            meta.setColumnsInfo(JSON.toJSONString(columns));
            meta.setVersionId(document.getCurrentVersionId());
            if (tableMetaMapper.insert(meta) != 1) {
                throw new IllegalStateException("表元数据保存失败");
            }
            log.info("表格导入完成 docId={}, table={}, rows={}", document.getDocId(), tableName, data.size());
        } catch (RuntimeException e) {
            // MySQL DDL 隐式提交，事务回滚不会移除新表；失败时显式清理。
            try {
                jdbcTemplate.execute("DROP TABLE `" + tableName + "`");
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return null;
    }

    private List<ColumnInfo> columns(List<String> headers) {
        Set<String> used = new HashSet<>();
        List<ColumnInfo> columns = new ArrayList<>(headers.size());
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            String base = header == null ? "" : header.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (base.isEmpty()) {
                base = String.valueOf(i + 1);
            }
            base = "col_" + base.substring(0, Math.min(base.length(), 48));
            String name = base;
            int suffix = 2;
            while (!used.add(name)) {
                name = base + "_" + suffix++;
            }
            columns.add(new ColumnInfo(i, header, name, "TEXT"));
        }
        return columns;
    }

    @Override
    public boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType) {
        return (fileType == FileType.EXCEL || fileType == FileType.CSV)
                && knowledgeBaseType == KnowledgeBaseType.DATA_QUERY;
    }

    public record ColumnInfo(int index, String originalHeader, String columnName, String dataType) { }
}
