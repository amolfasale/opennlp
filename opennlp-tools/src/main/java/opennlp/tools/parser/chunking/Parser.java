/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreemnets.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package opennlp.tools.parser.chunking;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import opennlp.model.AbstractModel;
import opennlp.model.MaxentModel;
import opennlp.model.TrainUtil;
import opennlp.model.TwoPassDataIndexer;
import opennlp.tools.chunker.ChunkSample;
import opennlp.tools.chunker.Chunker;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.parser.AbstractBottomUpParser;
import opennlp.tools.parser.ChunkContextGenerator;
import opennlp.tools.parser.ChunkSampleStream;
import opennlp.tools.parser.HeadRules;
import opennlp.tools.parser.Parse;
import opennlp.tools.parser.ParseSampleStream;
import opennlp.tools.parser.ParserChunkerSequenceValidator;
import opennlp.tools.parser.ParserEventTypeEnum;
import opennlp.tools.parser.ParserModel;
import opennlp.tools.parser.ParserType;
import opennlp.tools.parser.PosSampleStream;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSSample;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.util.HashSumEventStream;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.TrainingParameters;
import opennlp.tools.util.model.ModelType;
import opennlp.tools.util.model.ModelUtil;

/**
 * Class for a shift reduce style parser based on Adwait Ratnaparkhi's 1998 thesis.
 */
public class Parser extends AbstractBottomUpParser {

  private MaxentModel buildModel;
  private MaxentModel checkModel;

  private BuildContextGenerator buildContextGenerator;
  private CheckContextGenerator checkContextGenerator;

  private double[] bprobs;
  private double[] cprobs;

  private static final String TOP_START = START + TOP_NODE;
  private int topStartIndex;
  private Map<String, String> startTypeMap;
  private Map<String, String> contTypeMap;

  private int completeIndex;
  private int incompleteIndex;

  public Parser(ParserModel model, int beamSize, double advancePercentage) {
    this(model.getBuildModel(), model.getCheckModel(),
        new POSTaggerME(model.getParserTaggerModel(), 10, 0),
        new ChunkerME(model.getParserChunkerModel(),
            ChunkerME.DEFAULT_BEAM_SIZE,
            new ParserChunkerSequenceValidator(model.getParserChunkerModel()),
            new ChunkContextGenerator(ChunkerME.DEFAULT_BEAM_SIZE)),
            model.getHeadRules(), beamSize, advancePercentage);
  }
  
  public Parser(ParserModel model) {
    this(model, defaultBeamSize, defaultAdvancePercentage);
  }

  /**
   * Creates a new parser using the specified models and head rules.
   * @param buildModel The model to assign constituent labels.
   * @param checkModel The model to determine a constituent is complete.
   * @param tagger The model to assign pos-tags.
   * @param chunker The model to assign flat constituent labels.
   * @param headRules The head rules for head word perculation.
   */
  @Deprecated
  public Parser(MaxentModel buildModel, MaxentModel checkModel, POSTagger tagger, Chunker chunker, HeadRules headRules) {
  	this(buildModel,checkModel,tagger,chunker,headRules,defaultBeamSize,defaultAdvancePercentage);
  }

  /**
   * Creates a new parser using the specified models and head rules using the specified beam size and advance percentage.
   * @param buildModel The model to assign constituent labels.
   * @param checkModel The model to determine a constituent is complete.
   * @param tagger The model to assign pos-tags.
   * @param chunker The model to assign flat constituent labels.
   * @param headRules The head rules for head word perculation.
   * @param beamSize The number of different parses kept during parsing.
   * @param advancePercentage The minimal amount of probability mass which advanced outcomes must represent.
   * Only outcomes which contribute to the top "advancePercentage" will be explored.
   */
  @Deprecated
  public Parser(MaxentModel buildModel, MaxentModel checkModel, POSTagger tagger, Chunker chunker, HeadRules headRules, int beamSize, double advancePercentage) {
    super(tagger, chunker, headRules, beamSize, advancePercentage);
    this.buildModel = buildModel;
    this.checkModel = checkModel;
    bprobs = new double[buildModel.getNumOutcomes()];
    cprobs = new double[checkModel.getNumOutcomes()];
    this.buildContextGenerator = new BuildContextGenerator();
    this.checkContextGenerator = new CheckContextGenerator();
    startTypeMap = new HashMap<String, String>();
    contTypeMap = new HashMap<String, String>();
    for (int boi = 0, bon = buildModel.getNumOutcomes(); boi < bon; boi++) {
      String outcome = buildModel.getOutcome(boi);
      if (outcome.startsWith(START)) {
        //System.err.println("startMap "+outcome+"->"+outcome.substring(START.length()));
        startTypeMap.put(outcome, outcome.substring(START.length()));
      }
      else if (outcome.startsWith(CONT)) {
        //System.err.println("contMap "+outcome+"->"+outcome.substring(CONT.length()));
        contTypeMap.put(outcome, outcome.substring(CONT.length()));
      }
    }
    topStartIndex = buildModel.getIndex(TOP_START);
    completeIndex = checkModel.getIndex(COMPLETE);
    incompleteIndex = checkModel.getIndex(INCOMPLETE);
  }

  protected void advanceTop(Parse p) {
    buildModel.eval(buildContextGenerator.getContext(p.getChildren(), 0), bprobs);
    p.addProb(Math.log(bprobs[topStartIndex]));
    checkModel.eval(checkContextGenerator.getContext(p.getChildren(), TOP_NODE, 0, 0), cprobs);
    p.addProb(Math.log(cprobs[completeIndex]));
    p.setType(TOP_NODE);
  }

  protected Parse[] advanceParses(final Parse p, double probMass) {
    double q = 1 - probMass;
    /** The closest previous node which has been labeled as a start node. */
    Parse lastStartNode = null;
    /** The index of the closest previous node which has been labeled as a start node. */
    int lastStartIndex = -1;
    /** The type of the closest previous node which has been labeled as a start node. */
    String lastStartType = null;
    /** The index of the node which will be labeled in this iteration of advancing the parse. */
    int advanceNodeIndex;
    /** The node which will be labeled in this iteration of advancing the parse. */
    Parse advanceNode=null;
    Parse[] originalChildren = p.getChildren();
    Parse[] children = collapsePunctuation(originalChildren,punctSet);
    int numNodes = children.length;
    if (numNodes == 0) {
      return null;
    }
    //determines which node needs to be labeled and prior labels.
    for (advanceNodeIndex = 0; advanceNodeIndex < numNodes; advanceNodeIndex++) {
      advanceNode = children[advanceNodeIndex];
      if (advanceNode.getLabel() == null) {
        break;
      }
      else if (startTypeMap.containsKey(advanceNode.getLabel())) {
        lastStartType = startTypeMap.get(advanceNode.getLabel());
        lastStartNode = advanceNode;
        lastStartIndex = advanceNodeIndex;
        //System.err.println("lastStart "+i+" "+lastStart.label+" "+lastStart.prob);
      }
    }
    int originalAdvanceIndex = mapParseIndex(advanceNodeIndex,children,originalChildren);
    List<Parse> newParsesList = new ArrayList<Parse>(buildModel.getNumOutcomes());
    //call build
    buildModel.eval(buildContextGenerator.getContext(children, advanceNodeIndex), bprobs);
    double bprobSum = 0;
    while (bprobSum < probMass) {
      // The largest unadvanced labeling.
      int max = 0;
      for (int pi = 1; pi < bprobs.length; pi++) { //for each build outcome
        if (bprobs[pi] > bprobs[max]) {
          max = pi;
        }
      }
      if (bprobs[max] == 0) {
        break;
      }
      double bprob = bprobs[max];
      bprobs[max] = 0; //zero out so new max can be found
      bprobSum += bprob;
      String tag = buildModel.getOutcome(max);
      //System.out.println("trying "+tag+" "+bprobSum+" lst="+lst);
      if (max == topStartIndex) { // can't have top until complete
        continue;
      }
      //System.err.println(i+" "+tag+" "+bprob);
      if (startTypeMap.containsKey(tag)) { //update last start
        lastStartIndex = advanceNodeIndex;
        lastStartNode = advanceNode;
        lastStartType = startTypeMap.get(tag);
      }
      else if (contTypeMap.containsKey(tag)) {
        if (lastStartNode == null || !lastStartType.equals(contTypeMap.get(tag))) {
          continue; //Cont must match previous start or continue
        }
      }
      Parse newParse1 = (Parse) p.clone(); //clone parse
      if (createDerivationString) newParse1.getDerivation().append(max).append("-");
      newParse1.setChild(originalAdvanceIndex,tag); //replace constituent being labeled to create new derivation
      newParse1.addProb(Math.log(bprob));
      //check
      //String[] context = checkContextGenerator.getContext(newParse1.getChildren(), lastStartType, lastStartIndex, advanceNodeIndex);
      checkModel.eval(checkContextGenerator.getContext(collapsePunctuation(newParse1.getChildren(),punctSet), lastStartType, lastStartIndex, advanceNodeIndex), cprobs);
      //System.out.println("check "+lastStartType+" "+cprobs[completeIndex]+" "+cprobs[incompleteIndex]+" "+tag+" "+java.util.Arrays.asList(context));
      Parse newParse2 = newParse1;
      if (cprobs[completeIndex] > q) { //make sure a reduce is likely
        newParse2 = (Parse) newParse1.clone();
        if (createDerivationString) newParse2.getDerivation().append(1).append(".");
        newParse2.addProb(Math.log(cprobs[completeIndex]));
        Parse[] cons = new Parse[advanceNodeIndex - lastStartIndex + 1];
        boolean flat = true;
        //first
        cons[0] = lastStartNode;
        flat &= cons[0].isPosTag();
        //last
        cons[advanceNodeIndex - lastStartIndex] = advanceNode;
        flat &= cons[advanceNodeIndex - lastStartIndex].isPosTag();
        //middle
        for (int ci = 1; ci < advanceNodeIndex - lastStartIndex; ci++) {
          cons[ci] = children[ci + lastStartIndex];
          flat &= cons[ci].isPosTag();
        }
        if (!flat) { //flat chunks are done by chunker
          if (lastStartIndex == 0 && advanceNodeIndex == numNodes-1) { //check for top node to include end and begining punctuation
            //System.err.println("ParserME.advanceParses: reducing entire span: "+new Span(lastStartNode.getSpan().getStart(), advanceNode.getSpan().getEnd())+" "+lastStartType+" "+java.util.Arrays.asList(children));
            newParse2.insert(new Parse(p.getText(), p.getSpan(), lastStartType, cprobs[1], headRules.getHead(cons, lastStartType)));
          }
          else {
            newParse2.insert(new Parse(p.getText(), new Span(lastStartNode.getSpan().getStart(), advanceNode.getSpan().getEnd()), lastStartType, cprobs[1], headRules.getHead(cons, lastStartType)));
          }
          newParsesList.add(newParse2);
        }
      }
      if (cprobs[incompleteIndex] > q) { //make sure a shift is likely
        if (createDerivationString) newParse1.getDerivation().append(0).append(".");
        if (advanceNodeIndex != numNodes - 1) { //can't shift last element
          newParse1.addProb(Math.log(cprobs[incompleteIndex]));
          newParsesList.add(newParse1);
        }
      }
    }
    Parse[] newParses = new Parse[newParsesList.size()];
    newParsesList.toArray(newParses);
    return newParses;
  }

  @Deprecated
  public static AbstractModel train(opennlp.model.EventStream es, int iterations, int cut) throws java.io.IOException {
    return opennlp.maxent.GIS.trainModel(iterations, new TwoPassDataIndexer(es, cut));
  }

  public static ParserModel train(String languageCode, ObjectStream<Parse> parseSamples, HeadRules rules, TrainingParameters mlParams)
  throws IOException {
    
    System.err.println("Building dictionary");
 // TODO: Discuss and make dict cutoff configurable
    Dictionary mdict = buildDictionary(parseSamples, rules, 5); 
    
    parseSamples.reset();
    
    Map<String, String> manifestInfoEntries = new HashMap<String, String>();
    // TODO: Fix this, find a way to include train params in manifest ...
//    ModelUtil.addCutoffAndIterations(manifestInfoEntries, cut, iterations);
    
    // build
    System.err.println("Training builder");
    opennlp.model.EventStream bes = new ParserEventStream(parseSamples, rules, ParserEventTypeEnum.BUILD, mdict);
    HashSumEventStream hsbes = new HashSumEventStream(bes);
    AbstractModel buildModel = TrainUtil.train(hsbes, mlParams.getSettings("build"));
    manifestInfoEntries.put("Training-Builder-Eventhash", 
        hsbes.calculateHashSum().toString(16));
    
    parseSamples.reset();
    
    // tag
    POSModel posModel = POSTaggerME.train(languageCode, new PosSampleStream(parseSamples), 
        mlParams.getParameters("tagger"), null, null); // <- pass on name space corrected TrainingParameters ...
    
    parseSamples.reset();
    
    // chunk
    ChunkerModel chunkModel = ChunkerME.train(languageCode, 
        new ChunkSampleStream(parseSamples), // <- pass on name space corrected TrainingParameters ...
        new ChunkContextGenerator(), mlParams.getParameters("chunker"));
    
    parseSamples.reset();
    
    // check
    System.err.println("Training checker");
    opennlp.model.EventStream kes = new ParserEventStream(parseSamples, rules, ParserEventTypeEnum.CHECK);
    HashSumEventStream hskes = new HashSumEventStream(kes);
    AbstractModel checkModel = TrainUtil.train(hskes, mlParams.getSettings("check"));
    manifestInfoEntries.put("Training-Checker-Eventhash", 
        hskes.calculateHashSum().toString(16));
    
    // TODO: Remove cast for HeadRules
    return new ParserModel(languageCode, buildModel, checkModel,
        posModel, chunkModel, (opennlp.tools.parser.lang.en.HeadRules) rules,
        ParserType.CHUNKING, manifestInfoEntries);
  }
  
  public static ParserModel train(String languageCode, ObjectStream<Parse> parseSamples, HeadRules rules, int iterations, int cut)
      throws IOException {
    
    System.err.println("Building dictionary");
    Dictionary mdict = buildDictionary(parseSamples, rules, cut);
    
    parseSamples.reset();
    
    Map<String, String> manifestInfoEntries = new HashMap<String, String>();
    ModelUtil.addCutoffAndIterations(manifestInfoEntries, cut, iterations);
    
    // build
    System.err.println("Training builder");
    opennlp.model.EventStream bes = new ParserEventStream(parseSamples, rules, ParserEventTypeEnum.BUILD, mdict);
    HashSumEventStream hsbes = new HashSumEventStream(bes);
    AbstractModel buildModel = train(hsbes, iterations, cut);
    manifestInfoEntries.put("Training-Builder-Eventhash", 
        hsbes.calculateHashSum().toString(16));
    
    parseSamples.reset();
    
    // tag
    POSModel posModel = POSTaggerME.train(languageCode, new PosSampleStream(parseSamples), 
        ModelType.MAXENT, null, null, cut, iterations);
    
    parseSamples.reset();
    
    // chunk
    ChunkerModel chunkModel = ChunkerME.train(languageCode, 
        new ChunkSampleStream(parseSamples), cut, iterations,
        new ChunkContextGenerator());
    
    parseSamples.reset();
    
    // check
    System.err.println("Training checker");
    opennlp.model.EventStream kes = new ParserEventStream(parseSamples, rules, ParserEventTypeEnum.CHECK);
    HashSumEventStream hskes = new HashSumEventStream(kes);
    AbstractModel checkModel = train(hskes, iterations, cut);
    manifestInfoEntries.put("Training-Checker-Eventhash", 
        hskes.calculateHashSum().toString(16));
    
    // TODO: Remove cast for HeadRules
    return new ParserModel(languageCode, buildModel, checkModel,
        posModel, chunkModel, (opennlp.tools.parser.lang.en.HeadRules) rules,
        ParserType.CHUNKING, manifestInfoEntries);
  }
}
