package org.commoncrawl.tools;


import org.apache.commons.codec.binary.Base32;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3native.NativeS3FileSystem;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileAlreadyExistsException;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.NullOutputCommitter;
import org.apache.nutch.crawl.NutchWritable;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.parse.ParseData;
import org.apache.nutch.parse.ParseText;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.protocol.ProtocolStatus;
import org.apache.nutch.util.HadoopFSUtil;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TimingUtil;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.commoncrawl.warc.WarcWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class WarcExport extends Configured implements Tool {
  public static Logger LOG = LoggerFactory.getLogger(WarcExport.class);

  public static class CompleteData implements Writable {
    public Text url;
    public CrawlDatum datum;
    public Content content;
    public ParseText parseText;

    public CompleteData() {
      url = new Text();
      datum = new CrawlDatum();
      content = new Content();
      parseText = new ParseText();
    }

    public CompleteData(Text url, CrawlDatum datum, Content content, ParseText parseText) {
      this.url = url;
      this.datum = datum;
      this.content = content;
      if (parseText != null) {
        this.parseText = parseText;
      } else {
        this.parseText = new ParseText();
      }
    }

    public void readFields(DataInput in) throws IOException {
      url.readFields(in);
      datum.readFields(in);
      content.readFields(in);
      parseText.readFields(in);
    }

    public void write(DataOutput out) throws IOException {
      url.write(out);
      datum.write(out);
      content.write(out);
      parseText.write(out);
    }

    public String toString() {
      return "url=" + url.toString() + ", datum=" + datum.toString();
    }
  }

  public static class WarcOutputFormat extends FileOutputFormat<Text, CompleteData> {
    protected static class WarcRecordWriter implements RecordWriter<Text, CompleteData> {
      private DataOutputStream warcOut;
      private WarcWriter warcWriter;
      private DataOutputStream textWarcOut;
      private WarcWriter textWarcWriter;
      private URI warcinfoId;
      private URI textWarcinfoId;
      private final String CRLF = "\r\n";
      private final String COLONSP = ": ";
      private MessageDigest sha1 = null;
      private Base32 base32 = null;
      private boolean generateText;

      public WarcRecordWriter(FileSystem fs, JobConf job, Progressable progress, String filename, String textFilename,
                              String hostname, String publisher, String operator, String software, String isPartOf,
                              String description, boolean generateText) throws IOException {
        Path basedir = FileOutputFormat.getOutputPath(job);

        warcOut = fs.create(new Path(new Path(basedir, "warc"), filename), progress);
        warcWriter = new WarcWriter(warcOut);
        warcinfoId = warcWriter.writeWarcinfoRecord(filename, hostname, publisher, operator, software, isPartOf, description);

        this.generateText = generateText;
        if (generateText) {
          textWarcOut = fs.create(new Path(new Path(basedir, "text"), textFilename), progress);
          textWarcWriter = new WarcWriter(textWarcOut);
          textWarcinfoId = textWarcWriter.writeWarcinfoRecord(textFilename, hostname, publisher, operator, software, isPartOf, description);
        }

        base32 = new Base32();

        try {
          sha1 = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
          LOG.info("Unable to instantiate SHA1 MessageDigest object");
          throw new RuntimeException(e);
        }
      }

      protected String getSha1DigestWithAlg(byte[] bytes) {
        sha1.reset();
        return "sha1:" + base32.encodeAsString(sha1.digest(bytes));
      }

      public synchronized void write(Text key, CompleteData value) throws IOException {
        URI targetUri;

        try {
          targetUri = new URI(value.url.toString());
        } catch (URISyntaxException e) {
          return;
        }

        String ip = "0.0.0.0";
        Date date = null;
        boolean notModified = false;
        String verbatimResponseHeaders = null;
        String verbatimRequestHeaders = null;
        String headers = "";
        String statusLine = "";
        String crawlDelay = null;
        String payloadSignature = null;
        String blockSignature = null;
        String textBlockSignature = null;

        date = new Date(value.datum.getFetchTime());

        // This is for older crawl dbs that don't include the verbatim status line in the metadata
        ProtocolStatus pstatus = (ProtocolStatus)value.datum.getMetaData().get(Nutch.WRITABLE_PROTO_STATUS_KEY);
        if (pstatus == null) {
          return;
        } else {
          switch (pstatus.getCode()) {
            case ProtocolStatus.SUCCESS:
              statusLine = "HTTP/1.0 200 OK";
              break;
            case ProtocolStatus.TEMP_MOVED:
              statusLine = "HTTP/1.0 302 Found";
              break;
            case ProtocolStatus.MOVED:
              statusLine = "HTTP/1.0 301 Moved Permanently";
              break;
            case ProtocolStatus.NOTMODIFIED:
              statusLine = "HTTP/1.0 304 Not Modified";
              notModified = true;
              break;
            default:
              if (value.content.getMetadata().get(Nutch.FETCH_RESPONSE_VERBATIM_STATUS_KEY) == null) {
                LOG.info("Unknown or ambiguous protocol status");
                return;
              }
          }
        }

        boolean useVerbatimResponseHeaders = false;

        for (String name : value.content.getMetadata().names()) {
          if (name.equals(Nutch.FETCH_DEST_IP_KEY)) {
            ip = value.content.getMetadata().get(name);
          } else if (name.equals(Nutch.SEGMENT_NAME_KEY)) {
          } else if (name.equals(Nutch.FETCH_STATUS_KEY)) {
          } else if (name.equals(Nutch.SCORE_KEY)) {
          } else if (name.equals(Nutch.FETCH_REQUEST_VERBATIM_KEY)) {
            verbatimRequestHeaders = value.content.getMetadata().get(name);
          } else if (name.equals(Nutch.CRAWL_DELAY_KEY)) {
            crawlDelay = value.content.getMetadata().get(name);
          } else if (name.equals(Nutch.SIGNATURE_KEY)) {
            payloadSignature = "md5:" + value.content.getMetadata().get(name);
          } else if (name.equals(Nutch.FETCH_RESPONSE_VERBATIM_HEADERS_KEY)) {
            verbatimResponseHeaders = value.content.getMetadata().get(name);
            if (verbatimResponseHeaders.contains(CRLF)) {
              useVerbatimResponseHeaders = true;
            }
          } else if (name.equals(Nutch.FETCH_RESPONSE_VERBATIM_STATUS_KEY)) {
            statusLine = value.content.getMetadata().get(name);
          } else if (name.equals(Nutch.FETCH_RESPONSE_STATUS_CODE_KEY)) {
          } else {
            // We have to fix up a few headers because we don't have the raw responses
            if (name.equalsIgnoreCase("Content-Length")) {
              headers += "Content-Length: " + value.content.getContent().length + CRLF;
            } else if (name.equalsIgnoreCase("Content-Encoding")) {
            } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
            } else {
              headers += name + ": " + value.content.getMetadata().get(name) + CRLF;
            }
          }
        }

        String fetchDuration = value.datum.getMetaData().get(Nutch.WRITABLE_FETCH_DURATION_KEY).toString();
        if (value.datum.getMetaData().get(Nutch.WRITABLE_CRAWL_DELAY_KEY) != null) {
          crawlDelay = value.datum.getMetaData().get(Nutch.WRITABLE_CRAWL_DELAY_KEY).toString();
        }


        if (verbatimRequestHeaders == null) {
          LOG.info("No request headers!");
          return;
        }

        if (useVerbatimResponseHeaders && verbatimResponseHeaders != null) {
          headers = verbatimResponseHeaders;
        }

        StringBuilder requestsb = new StringBuilder(4096);
        requestsb.append(verbatimRequestHeaders);

        byte[] requestBytes = requestsb.toString().getBytes("utf-8");
        InputStream request = new ByteArrayInputStream(requestBytes);

        URI requestId = warcWriter.writeWarcRequestRecord(targetUri, ip, date, warcinfoId, request, requestBytes.length);

        if (notModified) {
          InputStream abbreviatedResponse;
          int abbreviatedResponseLength;

          /*
          warcWriter.writeWarcRevisitRecord(targetUri, ip, date, warcinfoId, requestId,
              WarcWriter.PROFILE_REVISIT_NOT_MODIFIED, payloadDigest, abbreviatedResponse, abbreviatedResponseLength);
              */
        } else {
          StringBuilder responsesb = new StringBuilder(4096);
          responsesb.append(statusLine).append(CRLF);
          responsesb.append(headers).append(CRLF);

          byte[] responseHeaderBytes = responsesb.toString().getBytes("utf-8");
          byte[] responseBytes = new byte[responseHeaderBytes.length + value.content.getContent().length];
          System.arraycopy(responseHeaderBytes, 0, responseBytes, 0, responseHeaderBytes.length);
          System.arraycopy(value.content.getContent(), 0, responseBytes, responseHeaderBytes.length,
              value.content.getContent().length);

          sha1.reset();
          payloadSignature = getSha1DigestWithAlg(value.content.getContent());

          sha1.reset();
          blockSignature = getSha1DigestWithAlg(responseBytes);

          InputStream response = new ByteArrayInputStream(responseBytes);

          URI responseId = warcWriter.writeWarcResponseRecord(targetUri, ip, date, warcinfoId, requestId,
              payloadSignature, blockSignature, response, responseBytes.length);

          // Write metadata record
          StringBuilder metadatasb = new StringBuilder(4096);
          Map<String, String> metadata = new LinkedHashMap<String, String>();

          if (fetchDuration != null) {
            metadata.put("fetchTimeMs", fetchDuration);
          }

          if (crawlDelay != null) {
            metadata.put("robotsCrawlDelay", crawlDelay);
          }

          for (Map.Entry<String, String> entry : metadata.entrySet()) {
            metadatasb.append(entry.getKey()).append(COLONSP).append(entry.getValue()).append(CRLF);
          }
          metadatasb.append(CRLF);

          byte[] metadataBytes = metadatasb.toString().getBytes("utf-8");
          InputStream metadataStream = new ByteArrayInputStream(metadataBytes);

          warcWriter.writeWarcMetadataRecord(targetUri, date, warcinfoId, responseId, null, metadataStream,
              metadataBytes.length);

          // Write text extract
          if (generateText && value.parseText != null) {
            final String text = value.parseText.getText();
            if (text != null) {
              byte[] conversionBytes = value.parseText.getText().getBytes("utf-8");
              if (conversionBytes.length != 0) {
                InputStream conversion = new ByteArrayInputStream(conversionBytes);

                textBlockSignature = getSha1DigestWithAlg(conversionBytes);
                textWarcWriter.writeWarcConversionRecord(targetUri, date, textWarcinfoId, responseId, textBlockSignature,
                  "text/plain", conversion, conversionBytes.length);
              }
            }
          }
        }
      }

      public synchronized void close(Reporter reporter) throws IOException {
        warcOut.close();
        if (generateText) {
          textWarcOut.close();
        }
      }
    }

    public RecordWriter<Text, CompleteData> getRecordWriter(FileSystem fs, JobConf job, String name,
                                                         Progressable progress) throws IOException {
      TaskID taskId = TaskAttemptID.forName(job.get("mapred.task.id")).getTaskID();
      int partition = taskId.getId();
      System.out.println("Partition: " + partition);

      /*
      int partition = job.getInt(JobContext.TASK_PARTITION, -1);
      if (partition == -1) {
        throw new IllegalArgumentException("This method can only be called from within a Job");
      }
      */


      NumberFormat numberFormat = NumberFormat.getInstance();
      numberFormat.setMinimumIntegerDigits(5);
      numberFormat.setGroupingUsed(false);

      SimpleDateFormat fileDate = new SimpleDateFormat("yyyyMMddHHmmss");
      fileDate.setTimeZone(TimeZone.getTimeZone("GMT"));

      String prefix = job.get("warc.export.prefix", "CC-CRAWL");
      String textPrefix = job.get("warc.export.textprefix", "CC-CRAWL-TEXT");
      String prefixDate = job.get("warc.export.date", fileDate.format(new Date()));

      String hostname = job.get("warc.export.hostname", "localhost");
      String publisher = job.get("warc.export.publisher", null);
      String operator = job.get("warc.export.operator", null);
      String software = job.get("warc.export.software", null);
      String isPartOf = job.get("warc.export.isPartOf", null);
      String description = job.get("warc.export.description", null);
      boolean generateText = job.getBoolean("warc.export.text", true);


      // WARC recommends - Prefix-Timestamp-Serial-Crawlhost.warc.gz
      String filename = prefix + "-" + prefixDate + "-" + numberFormat.format(partition) + "-" +
          hostname + ".warc.gz";
      String textFilename = textPrefix + "-" + prefixDate + "-" + numberFormat.format(partition) + "-" +
          hostname + ".warc.gz";

      return new WarcRecordWriter(fs, job, progress, filename, textFilename, hostname, publisher, operator, software,
          isPartOf, description, generateText);
    }

    @Override
    public void checkOutputSpecs(FileSystem ignored, JobConf job)
        throws FileAlreadyExistsException,
        InvalidJobConfException, IOException {
      // Ensure that the output directory is set and not already there
      Path outDir = getOutputPath(job);
      if (outDir == null && job.getNumReduceTasks() != 0) {
        throw new InvalidJobConfException("Output directory not set in JobConf.");
      }
      if (outDir != null) {
        FileSystem fs = outDir.getFileSystem(job);
        // normalize the output directory
        outDir = fs.makeQualified(outDir);
        setOutputPath(job, outDir);

        // get delegation token for the outDir's file system
        TokenCache.obtainTokensForNamenodes(job.getCredentials(),
            new Path[]{outDir}, job);

      }
    }
  }

  public static class ExportMapReduce extends Configured
      implements Mapper<Text, Writable, Text, NutchWritable>,
      Reducer<Text, NutchWritable, Text, CompleteData> {

    private NutchWritable tempWritable;

    public void configure(JobConf job) {
      setConf(job);
      tempWritable = new NutchWritable();
    }

    public void map(Text key, Writable value,
                    OutputCollector<Text, NutchWritable> output, Reporter reporter) throws IOException {

      String urlString = key.toString();
      if (urlString == null) {
        return;
      } else {
        key.set(urlString);
      }

      tempWritable.set(value);
      output.collect(key, tempWritable);
    }

    public void reduce(Text key, Iterator<NutchWritable> values,
                       OutputCollector<Text, CompleteData> output, Reporter reporter)
        throws IOException {
      Text url = key;
      CrawlDatum datum = null;
      Content content = null;
      ParseText parseText = null;

      while (values.hasNext()) {
        final Writable value = values.next().get(); // unwrap
        if (value instanceof CrawlDatum) {
          datum = (CrawlDatum)value;
        } else if (value instanceof ParseData) {
          ParseData parseData = (ParseData)value;
          // Get the robots meta data
          String robotsMeta = parseData.getMeta("robots");

          // Has it a noindex for this url?
          if (robotsMeta != null && robotsMeta.toLowerCase().indexOf("noindex") != -1) {
            return;
          }
        } else if (value instanceof ParseText) {
          parseText = (ParseText)value;
        } else if (value instanceof Content) {
          content = (Content)value;
        }
      }

      if (datum == null || content == null) {
        return;
      }

      if (datum.getStatus() != CrawlDatum.STATUS_FETCH_SUCCESS) {
        return;
      }

      CompleteData completeData = new CompleteData(url, datum, content, parseText);

      output.collect(url, completeData);

    }

    public void close() throws IOException { }
  }

  public String getHostname() {
    try {
      Process p = Runtime.getRuntime().exec("hostname -f");
      p.waitFor();
      BufferedReader in = new BufferedReader(
          new InputStreamReader(p.getInputStream()));
      String hostname = in.readLine();
      if (hostname != null && hostname.length() != 0 && p.exitValue() == 0) {
        return hostname;
      }
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    return "localhost";
  }

  public void export(Path outputDir, List<Path> segments,
                     boolean generateText) throws IOException {

    final JobConf job = new NutchJob(getConf());
    job.setJobName("WarcExport: " + outputDir.toString());

    LOG.info("Exporter: Text generation: " + generateText);

    for (final Path segment : segments) {
      LOG.info("ExporterMapReduces: adding segment: " + segment);
      FileSystem fs = segment.getFileSystem(getConf());

      FileInputFormat.addInputPath(job, new Path(segment, CrawlDatum.FETCH_DIR_NAME));

      Path parseDataPath = new Path(segment, ParseData.DIR_NAME);
      if (fs.exists(parseDataPath)) {
        FileInputFormat.addInputPath(job, parseDataPath);
      }

      Path parseTextPath = new Path(segment, ParseText.DIR_NAME);
      if (generateText) {
        if (fs.exists(parseTextPath)) {
          FileInputFormat.addInputPath(job, parseTextPath);
        } else {
          LOG.warn("ParseText path doesn't exist: " + parseTextPath.toString());
        }
      }

      FileInputFormat.addInputPath(job, new Path(segment, Content.DIR_NAME));
    }

    //FileInputFormat.addInputPath(job, new Path(crawlDb, CrawlDb.CURRENT_NAME));
    job.setInputFormat(SequenceFileInputFormat.class);

    job.setMapperClass(ExportMapReduce.class);
    job.setReducerClass(ExportMapReduce.class);

    job.setOutputFormat(WarcOutputFormat.class);
    job.setMapOutputValueClass(NutchWritable.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(CompleteData.class);

    // We compress ourselves, so this isn't necessary
    job.setBoolean(org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.COMPRESS, false);
    job.set("warc.export.hostname", getHostname());

    job.setBoolean("warc.export.text", generateText);

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    long start = System.currentTimeMillis();
    LOG.info("Exporter: starting at " + sdf.format(start));

    // S3 driver does an MD5 verification after uploading
    // Also, this is painfully slow because of S3's slow copy functions

    if (outputDir.getFileSystem(getConf()) instanceof NativeS3FileSystem) {
      job.setOutputCommitter(NullOutputCommitter.class);
    }

    // job.setBoolean(ExportMapReduce.URL_FILTERING, filter);
    // job.setBoolean(ExportMapReduce.URL_NORMALIZING, normalize);
    job.setReduceSpeculativeExecution(false);
    FileOutputFormat.setOutputPath(job, outputDir);

    try {
      JobClient.runJob(job);
      long end = System.currentTimeMillis();
      LOG.info("Exporter: finished at " + sdf.format(end) + ", elapsed: "
          + TimingUtil.elapsedTime(start, end));
    } finally {
      //FileSystem.get(job).delete(outputDir, true);
    }
  }


  public int run(String[] args) throws Exception {
    if (args.length < 2) {
      System.err
          .println("Usage: WarcExport <outputdir> (<segment> ... | -dir <segments>) [-notext]");
      return -1;
    }

    final Path outputDir = new Path(args[0]);

    final List<Path> segments = new ArrayList<Path>();
    boolean generateText = true;

    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("-dir")) {
        Path dir = new Path(args[++i]);
        FileSystem fs = dir.getFileSystem(getConf());
        FileStatus[] fstats = fs.listStatus(dir,
            HadoopFSUtil.getPassDirectoriesFilter(fs));
        Path[] files = HadoopFSUtil.getPaths(fstats);
        for (Path p : files) {
          segments.add(p);
        }
      } else if (args[i].equals("-notext")) {
        generateText = false;
      } else {
        segments.add(new Path(args[i]));
      }
    }

    try {
      export(outputDir, segments, generateText);
      return 0;
    } catch (final Exception e) {
      LOG.error("Exporter: " + StringUtils.stringifyException(e));
      return -1;
    }
  }

  public static void main(String[] args) throws Exception {
    final int res = ToolRunner.run(NutchConfiguration.create(),
        new WarcExport(), args);
    System.exit(res);
  }
}