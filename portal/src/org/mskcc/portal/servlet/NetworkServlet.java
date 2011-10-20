
package org.mskcc.portal.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;

import org.mskcc.portal.model.ProfileData;
import org.mskcc.portal.model.ProfileDataSummary;
import org.mskcc.portal.network.Network;
import org.mskcc.portal.network.NetworkIO;
import org.mskcc.portal.network.NetworkUtils;
import org.mskcc.portal.network.Node;
import org.mskcc.portal.oncoPrintSpecLanguage.GeneticTypeLevel;
import org.mskcc.portal.oncoPrintSpecLanguage.OncoPrintSpecification;
import org.mskcc.portal.oncoPrintSpecLanguage.ParserOutput;
import org.mskcc.portal.remote.GetCaseSets;
import org.mskcc.portal.remote.GetGeneticProfiles;
import org.mskcc.portal.util.GeneticProfileUtil;
import org.mskcc.portal.util.OncoPrintSpecificationDriver;
import org.mskcc.portal.util.ProfileMerger;
import org.mskcc.portal.util.XDebug;
import org.mskcc.cgds.model.CaseList;
import org.mskcc.cgds.model.GeneticProfile;
import org.mskcc.cgds.model.GeneticAlterationType;
import org.mskcc.cgds.dao.DaoException;
import org.mskcc.cgds.web_api.GetProfileData;

/**
 * Retrieving 
 * @author jj
 */
public class NetworkServlet extends HttpServlet {
    private static final String NODE_ATTR_IN_QUERY = "IN_QUERY";
    private static final String NODE_ATTR_PERCENT_ALTERED = "PERCENT_ALTERED";
    private static final String NODE_ATTR_PERCENT_MUTATED = "PERCENT_MUTATED";
    private static final String NODE_ATTR_PERCENT_CNA_AMPLIFIED = "PERCENT_CNA_AMPLIFIED";
    private static final String NODE_ATTR_PERCENT_CNA_GAINED = "PERCENT_CNA_GAINED";
    private static final String NODE_ATTR_PERCENT_CNA_HOM_DEL = "PERCENT_CNA_HOMOZYGOUSLY_DELETED";
    private static final String NODE_ATTR_PERCENT_CNA_HET_LOSS = "PERCENT_CNA_HEMIZYGOUSLY_DELETED";
    private static final String NODE_ATTR_PERCENT_MRNA_WAY_UP = "PERCENT_MRNA_WAY_UP";
    private static final String NODE_ATTR_PERCENT_MRNA_WAY_DOWN = "PERCENT_MRNA_WAY_DOWN";
    
    @Override
    public void doGet(HttpServletRequest req,
                      HttpServletResponse res) 
            throws ServletException, IOException {
        this.doPost(req, res);
    }
    
    /**
     * Processes Post Request.
     * 
     * @param req   HttpServletRequest Object.
     * @param res   HttpServletResponse Object.
     * @throws ServletException Servlet Error.
     * @throws IOException IO Error.
     */
    @Override
    public void doPost(HttpServletRequest req,
                      HttpServletResponse res)
            throws ServletException, IOException {
        res.setContentType("text/xml");
        try {
            XDebug xdebug = new XDebug( req );
            
            String xd = req.getParameter("xdebug");
            boolean logXDebug = xd!=null && xd.equals("1");
            
            if (logXDebug) {
                xdebug.logMsg(this, "<a href=\""+getNetworkServletUrl(req)+"\" target=\"_blank\">NetworkServlet URL</a>");
            }

            //  Get User Defined Gene List
            String geneListStr = req.getParameter(QueryBuilder.GENE_LIST);
            Set<String> queryGenes = new HashSet<String>(Arrays.asList(geneListStr.toUpperCase().split(" ")));

            //String geneticProfileIdSetStr = xssUtil.getCleanInput (req, QueryBuilder.GENETIC_PROFILE_IDS);

            String netSrc = req.getParameter("netsrc");
            
            Network network;
            try {
                xdebug.startTimer();
                if (netSrc.toUpperCase().equals("CGDS")) {
                    network = NetworkIO.readNetworkFromCGDS(queryGenes, true);
                } else {
                    network = NetworkIO.readNetworkFromCPath2(queryGenes, true);
                    if (logXDebug) {
                        xdebug.logMsg("GetPathwayCommonsNetwork", "<a href=\""+NetworkIO.getCPath2URL(queryGenes)
                                +"\" target=\"_blank\">cPath2 URL</a>");
                    }
                }
                final String netSize = req.getParameter("netsize");
                if (netSize!=null) {
                    if (netSize.equals("small")) {
                        NetworkUtils.pruneNetwork(network, new NetworkUtils.NodeSelector() {
                            public boolean select(Node node) {
                                String inQuery = (String)node.getAttribute("IN_QUERY");
                                return inQuery==null || !inQuery.equals("true");
                            }
                        });
                    } else if (netSize.equals("medium")) {
                        NetworkUtils.pruneNetwork(network, new NetworkUtils.NodeSelector() {
                            public boolean select(Node node) {
                                String inMedium = (String)node.getAttribute("IN_MEDIUM");
                                return inMedium==null || !inMedium.equals("true");
                            }
                        });
                    }
                }
                xdebug.stopTimer();
                xdebug.logMsg(this, "Successfully retrieved networks from " + netSrc
                        + ": took "+xdebug.getTimeElapsed()+"ms");
            } catch (Exception e) {
                xdebug.logMsg(this, "Failed retrieving networks from cPath2\n"+e.getMessage());
                network = new Network(); // send an empty network instead
            }

            if (!network.getNodes().isEmpty()) {
                
                Map<String, String> mapNodeIDSymbol = getMapNodeIDSymbol(network);
                
                // add attribute is_query to indicate if a node is in query genes
                // and get the list of genes in network
                xdebug.logMsg(this, "Retrieving data from CGDS...");
                
                ArrayList<String> netGenes = new ArrayList<String>();
                for (Node node : network.getNodes()) {
                    String ngnc = mapNodeIDSymbol.get(node.getId());

                    if (ngnc!=null) {
                        netGenes.add(ngnc);
                    }
                }

                //  Get User Selected Genetic Profiles
                xdebug.startTimer();
                HashSet<String> geneticProfileIdSet = new HashSet<String>();

                for (String geneticProfileIdsStr : req.getParameterValues(QueryBuilder.GENETIC_PROFILE_IDS)) {
                    geneticProfileIdSet.addAll(Arrays.asList(geneticProfileIdsStr.split(" ")));
                }

                String cancerTypeId = req.getParameter(QueryBuilder.CANCER_STUDY_ID);
                
                xdebug.stopTimer();
                xdebug.logMsg(this, "Got User Selected Genetic Profiles. Took "+xdebug.getTimeElapsed()+"ms");
                
                //  Get Genetic Profiles for Selected Cancer Type
                xdebug.startTimer();
                ArrayList<GeneticProfile> profileList = GetGeneticProfiles.getGeneticProfiles(cancerTypeId);

                String caseIds = req.getParameter(QueryBuilder.CASE_IDS);

                if (caseIds==null || caseIds.isEmpty()) {
                    String caseSetId = req.getParameter(QueryBuilder.CASE_SET_ID);
                        //  Get Case Sets for Selected Cancer Type
                        ArrayList<CaseList> caseSets = GetCaseSets.getCaseSets(cancerTypeId);
                        for (CaseList cs : caseSets) {
                            if (cs.getStableId().equals(caseSetId)) {
                                caseIds = cs.getCaseListAsString();
                                break;
                            }
                        }
                }
                
                xdebug.stopTimer();
                xdebug.logMsg(this, "Got Genetic Profiles for Selected Cancer Type. Took "+xdebug.getTimeElapsed()+"ms");

                // retrieve profile data from CGDS for new genes
                Set<GeneticAlterationType> alterationTypes = new HashSet();
                ArrayList<ProfileData> profileDataList = new ArrayList<ProfileData>();
                for (String profileId : geneticProfileIdSet) {
                    xdebug.startTimer();
                    GeneticProfile profile = GeneticProfileUtil.getProfile(profileId, profileList);
                    if( null == profile ){
                       continue;
                    }
                    alterationTypes.add(profile.getGeneticAlterationType());

                    GetProfileData remoteCall = new GetProfileData(profile, netGenes, caseIds);
                    ProfileData pData = remoteCall.getProfileData();
                    if( pData == null ){
                       System.err.println( "pData == null" );
                    }else{
                       if( pData.getGeneList() == null ){
                          System.err.println( "pData.getValidGeneList() == null" );
                       }
                    }
                    profileDataList.add(pData);
                
                    xdebug.stopTimer();
                    xdebug.logMsg(this, "Got profile data from CGDS for new genes for "
                            +profile.getProfileName()+". Took "+xdebug.getTimeElapsed()+"ms");
                }

                xdebug.startTimer();
                ProfileMerger merger = new ProfileMerger(profileDataList);
                xdebug.stopTimer();
                xdebug.logMsg(this, "Merged profiles. Took "+xdebug.getTimeElapsed()+"ms");
                
                xdebug.startTimer();
                ProfileData netMergedProfile = merger.getMergedProfile();
                ArrayList<String> netGeneList = netMergedProfile.getGeneList();

                double zScoreThreshold = Double.parseDouble(req.getParameter(QueryBuilder.Z_SCORE_THRESHOLD));

                ParserOutput netOncoPrintSpecParserOutput = OncoPrintSpecificationDriver.callOncoPrintSpecParserDriver(
                        StringUtils.join(netGeneList, " "), geneticProfileIdSet,
                        profileList, zScoreThreshold);

                OncoPrintSpecification netOncoPrintSpec = netOncoPrintSpecParserOutput.getTheOncoPrintSpecification();
                ProfileDataSummary netDataSummary = new ProfileDataSummary(netMergedProfile,
                        netOncoPrintSpec, zScoreThreshold );
                
                xdebug.stopTimer();
                xdebug.logMsg(this, "Got profile data summary. Took "+xdebug.getTimeElapsed()+"ms");

                // add attributes
                xdebug.startTimer();
                addCGDSDataAsNodeAttribute(network, netDataSummary, alterationTypes, mapNodeIDSymbol);
                xdebug.stopTimer();
                xdebug.logMsg(this, "Added node attributes. Took "+xdebug.getTimeElapsed()+"ms");
            }


            String graphml = NetworkIO.writeNetwork2GraphML(network, new NetworkIO.NodeLabelHandler() {
                // using HGNC gene symbol as label if available
                public String getLabel(Node node) {
                    String symbol = NetworkUtils.getSymbol(node);
                    if (symbol!=null) {
                        return symbol;
                    }
                    
                    Object strNames = node.getAttributes().get("PARTICIPANT_NAME");
                    if (strNames!=null) {
                        String[] names = strNames.toString().split(";",2);
                        if (names.length>0) {
                            return names[0];
                        }
                    }
                    
                    return node.getId();
                }
            });
            
            if (logXDebug) {
                writeXDebug(xdebug, res);
            }
            
            PrintWriter writer = res.getWriter();
            writer.write(graphml);
            writer.flush();
        } catch (DaoException e) {
            throw new ServletException (e);
        }
    }
    
    private Map<String, String> getMapNodeIDSymbol(Network network) {
        Map<String, String> map = new HashMap<String, String>();
        for (Node node : network.getNodes()) {
            String symbol = NetworkUtils.getSymbol(node);
            if (symbol!=null) {
                map.put(node.getId(), symbol);
            }
        }
        return map;
    }
    
    private void addCGDSDataAsNodeAttribute(Network network, ProfileDataSummary netDataSummary,
            Set<GeneticAlterationType> alterationTypes, Map<String, String> mapNodeIDSymbol) {
        for (Node node : network.getNodes()) {
            String gene = mapNodeIDSymbol.get(node.getId());
            if (gene==null) {
                continue;
            }
            
            node.addAttribute(NODE_ATTR_PERCENT_ALTERED, netDataSummary
                    .getPercentCasesWhereGeneIsAltered(gene));
            if (alterationTypes.contains(GeneticAlterationType.MUTATION_EXTENDED) ||
                    alterationTypes.contains(GeneticAlterationType.MUTATION_EXTENDED)) {
                node.addAttribute(NODE_ATTR_PERCENT_MUTATED, netDataSummary
                        .getPercentCasesWhereGeneIsMutated(gene));
            }

            if (alterationTypes.contains(GeneticAlterationType.COPY_NUMBER_ALTERATION)) {
                node.addAttribute(NODE_ATTR_PERCENT_CNA_AMPLIFIED, netDataSummary
                        .getPercentCasesWhereGeneIsAtCNALevel(gene, GeneticTypeLevel.Amplified));
                node.addAttribute(NODE_ATTR_PERCENT_CNA_GAINED, netDataSummary
                        .getPercentCasesWhereGeneIsAtCNALevel(gene, GeneticTypeLevel.Gained));
                node.addAttribute(NODE_ATTR_PERCENT_CNA_HOM_DEL, netDataSummary
                        .getPercentCasesWhereGeneIsAtCNALevel(gene, GeneticTypeLevel.HomozygouslyDeleted));
                node.addAttribute(NODE_ATTR_PERCENT_CNA_HET_LOSS, netDataSummary
                        .getPercentCasesWhereGeneIsAtCNALevel(gene, GeneticTypeLevel.HemizygouslyDeleted));
            }

            if (alterationTypes.contains(GeneticAlterationType.MRNA_EXPRESSION)) {
                node.addAttribute(NODE_ATTR_PERCENT_MRNA_WAY_UP, netDataSummary
                        .getPercentCasesWhereMRNAIsUpRegulated(gene));
                node.addAttribute(NODE_ATTR_PERCENT_MRNA_WAY_DOWN, netDataSummary
                        .getPercentCasesWhereMRNAIsDownRegulated(gene));
            }
        }
        
    }
    
    private String getNetworkServletUrl(HttpServletRequest req) {
        String geneListStr = req.getParameter(QueryBuilder.GENE_LIST);
        String geneticProfileIdsStr = req.getParameter(QueryBuilder.GENETIC_PROFILE_IDS);
        String cancerTypeId = req.getParameter(QueryBuilder.CANCER_STUDY_ID);
        String caseSetId = req.getParameter(QueryBuilder.CASE_SET_ID);
        String zscoreThreshold = req.getParameter(QueryBuilder.Z_SCORE_THRESHOLD);
        String netSrc = req.getParameter("netsrc");
        String netSize = req.getParameter("netsize");
        
        return "network.do?"+QueryBuilder.GENE_LIST+"="+geneListStr
                +"&"+QueryBuilder.GENETIC_PROFILE_IDS+"="+geneticProfileIdsStr
                +"&"+QueryBuilder.CANCER_STUDY_ID+"="+cancerTypeId
                +"&"+QueryBuilder.CASE_SET_ID+"="+caseSetId
                +"&"+QueryBuilder.Z_SCORE_THRESHOLD+"="+zscoreThreshold
                +"&netsrc="+netSrc
                +"&netsize="+netSize;
    }
    
    private void writeXDebug(XDebug xdebug, HttpServletResponse res) 
            throws ServletException, IOException {
        PrintWriter writer = res.getWriter();
        writer.write("<!--xdebug messages begin:\n");
        for (Object msg : xdebug.getDebugMessages()) {
            writer.write(((org.mskcc.portal.util.XDebugMessage)msg).getMessage());
            writer.write("\n");
        }
        writer.write("xdebug messages end-->\n");
    }
}
