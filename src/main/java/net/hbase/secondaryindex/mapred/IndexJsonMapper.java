package net.hbase.secondaryindex.mapred;

import java.io.IOException;
import java.util.*;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Writable;

import net.hbase.secondaryindex.util.Const;
import net.hbase.secondaryindex.util.JsonUtil;

/**
 * Just build index for json column.
 * 
 * @author mayanhui
 * 
 */
public class IndexJsonMapper extends
		TableMapper<ImmutableBytesWritable, Writable> {

	private boolean isBuildSingleIndex;

	private String column;
	private String jsonFields;

	private JsonUtil jsonUtil = new JsonUtil();

	@Override
	protected void setup(Context context) throws IOException,
			InterruptedException {
		column = context.getConfiguration().get(Const.HBASE_CONF_COLUMN_NAME);
		jsonFields = context.getConfiguration().get(Const.HBASE_CONF_JSON_NAME);
		isBuildSingleIndex = context.getConfiguration().getBoolean(
				Const.HBASE_CONF_ISBUILDSINGLEINDEX_NAME, true);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void map(ImmutableBytesWritable row, Result columns, Context context)
			throws IOException {
		String json = null;
		byte[] rowkey = row.get();
		byte[] cf = Const.COLUMN_FAMILY_CF1;
		byte[] qualifier = Const.COLUMN_RK;
		String[] arr = jsonFields.split(",", -1);

		try {
			for (KeyValue kv : columns.list()) {
				json = Bytes.toStringBinary(kv.getValue()); // json column value
				long ts = kv.getTimestamp();

				/* build single column index */
				for (String jf : arr) {
					Set<String> jfValueSet = new HashSet<String>();
					// json array
					if (json.startsWith(Const.JSON_ARRAY_START)) {
						jfValueSet = jsonUtil.evaluateDistinctArray(json, jf);
					} else {
						// single json object
						String jfValue = jsonUtil.evaluate(json, "$." + jf)
								.toString();
						jfValueSet.add(jfValue);
					}

					for (String jfValue : jfValueSet) {
						if (null != jfValue && jfValue.trim().length() > 0) {
							Put put = new Put(
									Bytes.toBytes(column
											+ Const.ROWKEY_DEFAULT_SEPARATOR
											+ jf
											+ Const.ROWKEY_DEFAULT_SEPARATOR
											+ jfValue), ts);
							put.add(cf, qualifier, rowkey);
							context.write(row, put);
						}
					}
				}

				/* build combined index */
				if (!isBuildSingleIndex) {
					List<String> jsonArr = jsonUtil.evaluateArray(json,
							jsonFields);

					for (String ja : jsonArr) {
						String[] jarr = ja.split(",", -1);
						Vector<String> source = new Vector<String>();
						for (int i = 0; i < jarr.length; i++) {
							source.add(arr[i] + Const.ROWKEY_DEFAULT_SEPARATOR
									+ jarr[i]);
						}
						Vector<Vector> comb = Combination
								.getLowerLimitCombinations(source, 2);
						if (null != comb && comb.size() > 0) {
							for (Vector v : comb) {
								String indexRowkey = column
										+ Const.ROWKEY_DEFAULT_SEPARATOR
										+ v.toString().replaceAll(", ", "_")
												.replaceAll("\\[", "")
												.replaceAll("\\]", "");
								Put put = new Put(Bytes.toBytes(indexRowkey));
								put.add(cf, qualifier, rowkey);
								context.write(row, put);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Error: " + e.getMessage() + ", Row: "
					+ Bytes.toString(row.get()) + ", Value: " + json);
		}
	}

}
