package org.bitbucket.openkilda.wfm.topology.islstats;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.opentsdb.bolt.OpenTsdbBolt;
import org.apache.storm.opentsdb.bolt.TupleOpenTsdbDatapointMapper;
import org.apache.storm.opentsdb.client.OpenTsdbClient;
import org.apache.storm.topology.TopologyBuilder;

import org.bitbucket.openkilda.wfm.topology.AbstractTopology;
import org.bitbucket.openkilda.wfm.topology.Topology;
import org.bitbucket.openkilda.wfm.topology.islstats.bolts.IslStatsBolt;


import java.io.File;


public class IslStatsTopology extends AbstractTopology {
    private static final Logger logger = LogManager.getLogger(IslStatsTopology.class);

    private final String topoName = "IslStatsTopology";
    private final int parallelism = 1;

    private final String topic = "kilda-test";

    public IslStatsTopology(File file) {
        super(file);
    }

    public static void main(String[] args) throws Exception {

        //If there are arguments, we are running on a cluster; otherwise, we are running locally
        if (args != null && args.length > 0) {
            File file = new File(args[1]);
            Config conf = new Config();
            conf.setDebug(false);
            IslStatsTopology statsTopology = new IslStatsTopology(file);
            StormTopology topo = statsTopology.createTopology();

            conf.setNumWorkers(statsTopology.parallelism);
            StormSubmitter.submitTopology(args[0], conf, topo);
        } else {
            logger.info("starting islStatsTopo in local mode");
            File file = new File(IslStatsTopology.class.getResource(Topology.TOPOLOGY_PROPERTIES).getFile());
            Config conf = new Config();
            conf.setDebug(false);
            IslStatsTopology statsTopology = new IslStatsTopology(file);
            StormTopology topo = statsTopology.createTopology();

            conf.setMaxTaskParallelism(3);

            LocalCluster cluster = new LocalCluster();
            cluster.submitTopology(statsTopology.topologyName, conf, topo);

            Thread.sleep(60000000);
            cluster.shutdown();
        }
    }


    public StormTopology createTopology() {
        final String clazzName = this.getClass().getSimpleName();
        logger.debug("Building Topology - " + clazzName );

        TopologyBuilder builder = new TopologyBuilder();

        checkAndCreateTopic(topic);

        final String spoutName = topic + "-spout";
        logger.debug("connecting to " + topic + " topic");
        builder.setSpout(spoutName, createKafkaSpout(topic, clazzName));

        final String verifyIslStatsBoltName = IslStatsBolt.class.getSimpleName();
        IslStatsBolt verifyIslStatsBolt = new IslStatsBolt();
        logger.debug("starting " + verifyIslStatsBoltName + " bolt");
        builder.setBolt(verifyIslStatsBoltName, verifyIslStatsBolt, parallelism).shuffleGrouping(spoutName);

        //TODO: fix this such that it is not hardcoded
        OpenTsdbClient.Builder tsdbBuilder = OpenTsdbClient.newBuilder("http://opentsdb.pendev:4242")
                .sync(30_000).returnDetails();
        OpenTsdbBolt openTsdbBolt = new OpenTsdbBolt(tsdbBuilder, TupleOpenTsdbDatapointMapper.DEFAULT_MAPPER)
                .withBatchSize(10)
                .withFlushInterval(2)
                .failTupleForFailedMetrics();
        builder.setBolt("opentsdb", openTsdbBolt, parallelism).shuffleGrouping(verifyIslStatsBoltName);

        return builder.createTopology();
    }
}
