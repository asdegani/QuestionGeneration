// Question Generation via Overgenerating Transformations and Ranking
// Copyright (c) 2008, 2009 Carnegie Mellon University.  All Rights Reserved.
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 2
// of the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
//
// For more information, bug reports, fixes, contact:
//    Michael Heilman
//	  Carnegie Mellon University
//	  mheilman@cmu.edu
//	  http://www.cs.cmu.edu/~mheilman



package edu.cmu.ark;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import edu.stanford.nlp.trees.Tree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

//import java.text.NumberFormat;
//import weka.classifiers.functions.LinearRegression;
//import edu.cmu.ark.ranking.WekaLinearRegressionRanker;


/**
 * Wrapper class for outputting a (ranked) list of questions given an entire document,
 * not just a sentence.  It wraps the three stages discussed in the technical report and calls each in turn 
 * (along with parsing and other preprocessing) to produce questions.
 * 
 * This is the typical class to use for running the system via the command line. 
 * 
 * Example usage:
 * 
    java -server -Xmx800m -cp lib/weka-3-6.jar:lib/stanford-parser-2008-10-26.jar:bin:lib/jwnl.jar:lib/commons-logging.jar:lib/commons-lang-2.4.jar:lib/supersense-tagger.jar:lib/stanford-ner-2008-05-07.jar:lib/arkref.jar \
	edu/cmu/ark/QuestionAsker \
	--verbose --simplify --group \
	--model models/linear-regression-ranker-06-24-2010.ser.gz \
	--prefer-wh --max-length 30 --downweight-pro
 * 
 * @author mheilman@cs.cmu.edu
 *
 */
public class QuestionServer {


	public QuestionServer(){
	}
	
	
	
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		String[] askerArgs;
		askerArgs = new String[]{"--verbose", "--model", "QuestionGeneration/models/linear-regression-ranker-reg500.ser.gz",
			"--prefer-wh", "--max-length", "60", "--downweight-pro"};
		QuestionAsker.init(askerArgs);
		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
		server.createContext("/questiongenerator/api/generateQuestionAnswerPairFromText", new QuestionGeneratingHandler());
		server.start();



	}

	static class QuestionGeneratingHandler implements HttpHandler {

		public void handle(HttpExchange t) throws IOException {
			if (t.getRequestMethod().equalsIgnoreCase("Post")){
				InputStream request = t.getRequestBody();
				Scanner s = new Scanner(request).useDelimiter("\\A");
				String requestBody = s.hasNext() ? s.next() : "";

				String response = QuestionAsker.getTopQuestion(requestBody);

				t.sendResponseHeaders(200, response.getBytes().length);
				OutputStream os = t.getResponseBody();
				os.write(response.getBytes());
				os.close();
			}
			else {
				String response = "This is the response";
				t.sendResponseHeaders(200, response.getBytes().length);
				OutputStream os = t.getResponseBody();
				os.write(response.getBytes());
				os.close();
			}
		}
	}

	static class QuestionAsker{
		private static QuestionTransducer qt;
		private static InitialTransformationStep trans;
		private static QuestionRanker qr;
		private static List<Question> outputQuestionList;
		private static Tree parsed;
		private static boolean avoidFreqWords;
		private static boolean downweightPronouns;
		private static boolean preferWH;
		private static boolean justWH;
		private static Integer maxLength;
		private static boolean printVerbose;

		public static void init(String[] args){
			qt = new QuestionTransducer();
			trans = new InitialTransformationStep();
			qr = null;


			qt.setAvoidPronounsAndDemonstratives(false);

			//pre-load
			AnalysisUtilities.getInstance();

			printVerbose = false;
			String modelPath = null;

			outputQuestionList = new ArrayList<Question>();
			preferWH = false;
			boolean doNonPronounNPC = false;
			boolean doPronounNPC = true;
			maxLength = 1000;
			downweightPronouns = false;
			avoidFreqWords = false;
			boolean dropPro = true;
			justWH = false;

			for(int i=0;i<args.length;i++){
				if(args[i].equals("--debug")){
					GlobalProperties.setDebug(true);
				}else if(args[i].equals("--verbose")){
					printVerbose = true;
				}else if(args[i].equals("--model")){ //ranking model path
					modelPath = args[i+1];
					i++;
				}else if(args[i].equals("--keep-pro")){
					dropPro = false;
				}else if(args[i].equals("--downweight-pro")){
					dropPro = false;
					downweightPronouns = true;
				}else if(args[i].equals("--downweight-frequent-answers")){
					avoidFreqWords = true;
				}else if(args[i].equals("--properties")){
					GlobalProperties.loadProperties(args[i+1]);
				}else if(args[i].equals("--prefer-wh")){
					preferWH = true;
				}else if(args[i].equals("--just-wh")){
					justWH = true;
				}else if(args[i].equals("--full-npc")){
					doNonPronounNPC = true;
				}else if(args[i].equals("--no-npc")){
					doPronounNPC = false;
				}else if(args[i].equals("--max-length")){
					maxLength = new Integer(args[i+1]);
					i++;
				}
			}

			qt.setAvoidPronounsAndDemonstratives(dropPro);
			trans.setDoPronounNPC(doPronounNPC);
			trans.setDoNonPronounNPC(doNonPronounNPC);

			if(modelPath != null){
				System.err.println("Loading question ranking models from "+modelPath+"...");
				qr = new QuestionRanker();
				qr.loadModel(modelPath);
			}
		}

		public static String getTopQuestion(String doc){
			try{
				if(GlobalProperties.getDebug()) System.err.println("\nInput Text:");

					outputQuestionList.clear();

					if(doc.length() == 0){
						return null;
					}

				Gson gson = new Gson();
				QuestionText q = gson.fromJson(doc, QuestionText.class);

					long startTime = System.currentTimeMillis();
					List<String> sentences = AnalysisUtilities.getSentences(q.text);

					//iterate over each segmented sentence and generate questions
					List<Tree> inputTrees = new ArrayList<Tree>();

					for(String sentence: sentences){
						if(GlobalProperties.getDebug()) System.err.println("Question Asker: sentence: "+sentence);

						parsed = AnalysisUtilities.getInstance().parseSentence(sentence).parse;
						inputTrees.add(parsed);
					}

					if(GlobalProperties.getDebug()) System.err.println("Seconds Elapsed Parsing:\t"+((System.currentTimeMillis()-startTime)/1000.0));

					//step 1 transformations
					List<Question> transformationOutput = trans.transform(inputTrees);

					//step 2 question transducer
					for(Question t: transformationOutput){
						if(GlobalProperties.getDebug()) System.err.println("Stage 2 Input: "+t.getIntermediateTree().yield().toString());
						qt.generateQuestionsFromParse(t);
						outputQuestionList.addAll(qt.getQuestions());
					}

					//remove duplicates
					QuestionTransducer.removeDuplicateQuestions(outputQuestionList);

					//step 3 ranking
					if(qr != null){
						qr.scoreGivenQuestions(outputQuestionList);
						boolean doStemming = true;
						QuestionRanker.adjustScores(outputQuestionList, inputTrees, avoidFreqWords, preferWH, downweightPronouns, doStemming);
						QuestionRanker.sortQuestions(outputQuestionList, false);
					}

					for(Question question: outputQuestionList){
						if(question.getTree().getLeaves().size() > maxLength){
							continue;
						}
						if(justWH && question.getFeatureValue("whQuestion") != 1.0){
							continue;
						}

						if(GlobalProperties.getDebug()) System.err.println("Seconds Elapsed Total:\t"+((System.currentTimeMillis()-startTime)/1000.0));

						return gson.toJson(readyQuestion(question));

					}
			}catch(Exception e){
				e.printStackTrace();
				return null;
			}

			return null;
		}

		private static Response readyQuestion(Question question){
			Response ans = new Response();
			ans.question = question.yield();
			ans.answer = AnalysisUtilities.getCleanedUpYield(question.getAnswerPhraseTree());
			return ans;

			/*System.out.print(question.yield());
			if(printVerbose) System.out.print("\t"+AnalysisUtilities.getCleanedUpYield(question.getSourceTree()));
			Tree ansTree = question.getAnswerPhraseTree();
			if(printVerbose) System.out.print("\t");
			if(ansTree != null){
				if(printVerbose) System.out.print(AnalysisUtilities.getCleanedUpYield(question.getAnswerPhraseTree()));
			}
			if(printVerbose) System.out.print("\t"+question.getScore());
			//System.err.println("Answer depth: "+question.getFeatureValue("answerDepth"));

			System.out.println();*/
		}

		public static class QuestionText{
			private String text;

			public QuestionText(){
			}
		}

		public static class Response{
			private String question;
			private String answer;

			public Response(){
			}
		}
	}
}
