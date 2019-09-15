/*
 * Copyright 2019 PRImA Research Lab, University of Salford, United Kingdom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.primaresearch.googleocr;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.primaresearch.dla.page.Page;
import org.primaresearch.dla.page.io.xml.PageXmlInputOutput;
import org.primaresearch.dla.page.layout.PageLayout;
import org.primaresearch.dla.page.layout.physical.Region;
import org.primaresearch.dla.page.layout.physical.shared.RegionType;
import org.primaresearch.dla.page.layout.physical.text.impl.Glyph;
import org.primaresearch.dla.page.layout.physical.text.impl.TextLine;
import org.primaresearch.dla.page.layout.physical.text.impl.TextRegion;
import org.primaresearch.dla.page.layout.physical.text.impl.Word;
import org.primaresearch.maths.geometry.Polygon;
import org.primaresearch.maths.geometry.Rect;
import org.primaresearch.shared.variable.VariableValue;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Block;
import com.google.cloud.vision.v1.Block.BlockType;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Feature.Type;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.google.cloud.vision.v1.ImageContext;
import com.google.cloud.vision.v1.Paragraph;
import com.google.cloud.vision.v1.Symbol;
import com.google.cloud.vision.v1.TextAnnotation;
import com.google.cloud.vision.v1.LocalizedObjectAnnotation;
import com.google.cloud.vision.v1.TextAnnotation.DetectedBreak.BreakType;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

/**
 * Experimental tool to call Google Cloud Vision OCR and save the result as PAGE XML.
 *  
 * @author Christian Clausner
 *
 */
public class GoogleCloudOcrToPage {
	
	public static boolean debug = false;

	/**
	 * Entry point
	 * @param args -img [input image], -output [path to XML file], -lang [language code], -credentials [json file with service key]
	 */
	public static void main(String[] args) {
		if (args.length == 0) {
			showUsage();
			System.exit(0);
		}

		//Parse arguments
		String imageFilePath = null;
		String outputXmlFilePath = null;
		String language = null;
		String credentialsFilePath = null;
		String mode = "ocr";
		for (int i=0; i<args.length; i++) {
			if ("-img".equals(args[i])) {
				i++;
				imageFilePath = args[i];
			}
			else if ("-output".equals(args[i])) {
				i++;
				outputXmlFilePath = args[i];
			}
			else if ("-lang".equals(args[i])) {
				i++;
				language = args[i];
			}
			else if ("-credentials".equals(args[i])) {
				i++;
				credentialsFilePath = args[i];
			}
			else if ("-mode".equals(args[i])) {
				i++;
				mode = args[i];
			}
			else if ("-debug".equals(args[i])) {
				debug = true;
			}
		}
		
		if (debug) {
			System.out.println("img: "+imageFilePath);
			System.out.println("output: "+outputXmlFilePath);
			System.out.println("lang: "+language);
			System.out.println("credentials: "+credentialsFilePath);
			System.out.println("mode: "+mode);
		}
		
		if (imageFilePath == null || outputXmlFilePath == null || credentialsFilePath == null) {
			showUsage();
			System.exit(0);
		}
			
		GoogleCloudOcrToPage tool = new GoogleCloudOcrToPage(imageFilePath, outputXmlFilePath, language, credentialsFilePath, mode);
		tool.run();
		System.out.println("Exit code: " + tool.getErrorCode());
		
		System.exit(tool.getErrorCode());
	}

	/**
	 * Print usage help to stdout
	 */
	private static void showUsage() {
		System.out.println("Google Cloud Vision OCR To PAGE XML");
		System.out.println("");
		System.out.println("PRImA Research Lab, University of Salford, UK");
		System.out.println("");
		System.out.println("Arguments:");
		System.out.println("");
		System.out.println("  -img <Image file>        Image file to process.");
		System.out.println("");
		System.out.println("  -output <XML file>       PAGE XML output file path.");
		System.out.println("");
		System.out.println("  -lang <Language code>    Language hint (e.g. 'en').");
		System.out.println("");
		System.out.println("  -credentials <json file> Google cloud API service key file.");
		System.out.println("");
		System.out.println("  -mode <ocr|object>       Recognition mode (optional, default: ocr).");
		System.out.println("");
		System.out.println("  -debug                   Enable debug output.");
	}

	
	private int errorCode = 0; // 0 = no error 
	private String imageFilePath = null;
	private String outputXmlFilePath = null;
	private String language = null;
	private String credentialsFilePath = null;
	private String mode = null;
	private int maxX = 100;
	private int maxY = 100;
	
	/** 
	 * Constructor
	 * @param imageFilePath Input image
	 * @param outputXmlFilePath Output target
	 * @param language E.g. 'en'
	 * @param credentialsFilePath JSON file with Google service key
	 * @param mode 'ocr' or 'object'
	 */
	public GoogleCloudOcrToPage(String imageFilePath, String outputXmlFilePath,
			String language, String credentialsFilePath, String mode) {
		super();
		this.imageFilePath = imageFilePath;
		this.outputXmlFilePath = outputXmlFilePath;
		this.language = language;	
		this.credentialsFilePath = credentialsFilePath;		
		this.mode = mode;
	}
	
	/**
	 * <ul>
	 * <li>1: Web service returned an error</li>
	 * <li>2: No page in result</li>
	 * <li>3: IO exception</li>
	 * <li>4: File not found exception</li>
	 * <li>5: General exception</li>
	 * <li>6: Image not found</li>
	 * <li>7: Upload size limit exceeded</li>
	 * </ul> 
	 */
	public int getErrorCode() {
		return errorCode;
	}

	/**
	 * Call Google API and save result
	 */
	public void run() {
		if (debug)
			System.out.println("Start");		
		
		//Check image file size
		File imageFile = new File(imageFilePath);
		if (!imageFile.exists()) {
			errorCode = 6;
			return;
		}
		if (imageFile.length() > 10485760) { //Upload limit
			errorCode = 7;
			return;
		}			
	
	    // Builds the image annotation request
	    GoogleCredentials credentials;
		try {
			credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsFilePath))
					.createScoped(Lists.newArrayList("https://www.googleapis.com/auth/cloud-platform"));

			ImageAnnotatorSettings imageAnnotatorSettings =
				    ImageAnnotatorSettings.newBuilder()
				    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
				    .build();
			
		    // Instantiates a client
			try (ImageAnnotatorClient vision = ImageAnnotatorClient.create(imageAnnotatorSettings)) {

				// The path to the image file to annotate
				String fileName = this.imageFilePath;

				// Reads the image file into memory
				Path path = Paths.get(fileName);
				byte[] data = Files.readAllBytes(path);
				ByteString imgBytes = ByteString.copyFrom(data);

				List<AnnotateImageRequest> requests = new ArrayList<>();
				
				Image img = Image.newBuilder().setContent(imgBytes).build();
				
				Feature feat = null;
				if ("object".equals(mode))
					feat = Feature.newBuilder().setType(Type.OBJECT_LOCALIZATION).build();
				else //ocr
					feat = Feature.newBuilder().setType(Type.DOCUMENT_TEXT_DETECTION).build();
				
				ImageContext context = ImageContext.newBuilder().addLanguageHints(language).build();
				
				AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
						.addFeatures(feat)
						.setImage(img)
						.setImageContext(context)
						.build();
				requests.add(request);

				// Performs OCR on the image file
				BatchAnnotateImagesResponse response = vision.batchAnnotateImages(requests);
				List<AnnotateImageResponse> responses = response.getResponsesList();

				for (AnnotateImageResponse res : responses) {
					if (res.hasError()) {
						System.out.printf("Error: %s\n", res.getError().getMessage());
						errorCode = 1;
						return;
					}
					
					if ("object".equals(mode)) {
						try {
							BufferedImage bimg = ImageIO.read(new File(fileName));
							maxX = bimg.getWidth();
							maxY = bimg.getHeight();
						} catch (Exception exc) {
							exc.printStackTrace();
						}
						
						List<LocalizedObjectAnnotation> annotations = res.getLocalizedObjectAnnotationsList();
						if (!annotations.isEmpty()) {
							Page page = handleObjectAnnotations(annotations);
							if (page != null) {
								PageXmlInputOutput.writePage(page, outputXmlFilePath);
							}
							else
								errorCode = 8;
						}
						//for (EntityAnnotation annotation : annotations) {
			          	//	annotation.getAllFields().forEach((k, v) ->
			            //  System.out.printf("%s : %s\n", k, v.toString()));
					} //ocr
					else {
						TextAnnotation textAnno = res.getFullTextAnnotation();
						if (debug)
							System.out.println(""+textAnno.getPagesCount()+" Pages");
						
						if (textAnno.getPagesCount() > 0) {
							Page page = handlePage(textAnno.getPages(0));
							if (page != null) {
								PageXmlInputOutput.writePage(page, outputXmlFilePath);
							}
							else
								errorCode = 2;
						}
					}

				}
			} catch (IOException e) {
				e.printStackTrace();
				errorCode = 3;
			}
	    
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
			errorCode = 4;
		} catch (Exception e1) {
			e1.printStackTrace();
			errorCode = 5;
		}
		
		if (debug)
			System.out.println("Finished");
	}
	
	private Page handlePage(com.google.cloud.vision.v1.Page googlePage) {
		Page page = new Page();
		
		//Size
		page.getLayout().setSize(googlePage.getWidth(), googlePage.getHeight());
		maxX = googlePage.getWidth();
		maxY = googlePage.getHeight();
		
		//Image filename
		String filename = imageFilePath;
		int pos = filename.lastIndexOf(File.separator);
		if (pos > 0)
			filename = filename.substring(pos);
		page.setImageFilename(filename);
		
		for (int i=0; i<googlePage.getBlocksCount(); i++) {
			handleBlock(googlePage.getBlocks(i), page.getLayout());
		}
		
		return page;
	}
	
	private void handleBlock(Block block, PageLayout pageLayout) {
		RegionType regionType = getRegionType(block.getBlockType()); 
		
		//Text region? -> Use paragraphs
		if (regionType == RegionType.TextRegion) {
			//Paragraphs
			for (int i=0; i<block.getParagraphsCount(); i++) {
				handleParagraph(block.getParagraphs(i), pageLayout);
			}
		}
		//Other regions
		else {
			Polygon coords = convertToPolygon(block.getBoundingBox());
			if (coords.getSize() > 2) {
				Region region = pageLayout.createRegion(regionType);
				region.setCoords(coords);
			}
		}
		
	}
	
	private void handleParagraph(Paragraph paragraph, PageLayout pageLayout) {
		Polygon coords = convertToPolygon(paragraph.getBoundingBox());
		if (coords.getSize() > 2) {
			TextRegion region = (TextRegion)pageLayout.createRegion(RegionType.TextRegion);
			region.setCoords(coords);
			
			//Words
			TextLine currentTextLine = null;
			for (int i=0; i<paragraph.getWordsCount(); i++) {
				com.google.cloud.vision.v1.Word googleWord = paragraph.getWords(i);
				
				coords = convertToPolygon(googleWord.getBoundingBox());
				if (coords.getSize() > 2) {
					if (currentTextLine == null)
						currentTextLine = region.createTextLine();
					
					Word word = currentTextLine.createWord();
					word.setCoords(coords);
					
					BreakType breakType = handleSymbols(googleWord, word);
					
					//Text
					String txt = word.composeText(false, false);
					if (breakType == BreakType.HYPHEN)
						txt += "-";
					word.setText(txt);

					//Check for whitespace
					if (breakType == BreakType.LINE_BREAK
							|| breakType == BreakType.EOL_SURE_SPACE
							|| breakType == BreakType.HYPHEN) {
						finishTextLine(currentTextLine);
						currentTextLine = null;
					}
					
					//Confidence
					word.setConfidence((double)googleWord.getConfidence());
				}
			}
			
			if (currentTextLine != null)
				finishTextLine(currentTextLine);
			
			//Text
			region.composeText(true, false);
		}
	}
	
	private BreakType handleSymbols(com.google.cloud.vision.v1.Word googleWord, Word word) {
		BreakType wordBreakType = BreakType.UNKNOWN;
		for (int i=0; i<googleWord.getSymbolsCount(); i++) {
			Symbol symbol = googleWord.getSymbols(i);
			
			if (symbol.getProperty().hasDetectedBreak()) {
				BreakType symbolBreakType = symbol.getProperty().getDetectedBreak().getType();
				if (symbolBreakType != BreakType.UNKNOWN)
					wordBreakType = symbolBreakType;
			}
			Polygon coords = convertToPolygon(symbol.getBoundingBox());
			if (coords.getSize() > 2) {
				Glyph glyph = word.createGlyph();
				glyph.setCoords(coords);
				
				//Text
				glyph.setText(symbol.getText());
				
				//Confidence
				glyph.setConfidence((double)symbol.getConfidence());
			}
		}
		return wordBreakType;
	}
	
	private void finishTextLine(TextLine line) {
		//Only one child word? -> Clone polygon
		if (line.getTextObjectCount() == 1) {
			line.setCoords(line.getTextObject(0).getCoords().clone());
		}
		//Calculate bounding box
		else {
			//Calculate text line bounding box from words
			int l = Integer.MAX_VALUE;
			int r = 0;
			int t = Integer.MAX_VALUE;
			int b = 0;
			for (int i=0; i<line.getTextObjectCount(); i++) {
				Word word = (Word)line.getTextObject(i);
				Rect box = word.getCoords().getBoundingBox();
				if (box.left < l)
					l = box.left; 
				if (box.right > r)
					r = box.right; 
				if (box.top < t)
					t = box.top; 
				if (box.bottom > b)
					b = box.bottom; 
			}
			Polygon coords = new Polygon();
			coords.addPoint(l, t);
			coords.addPoint(r, t);
			coords.addPoint(r, b);
			coords.addPoint(l, b);
			line.setCoords(coords);
		}
		
		//Text
		line.composeText(true, false);
	}
	
	/** Google bounding box to PAGe polygon */
	private Polygon convertToPolygon(com.google.cloud.vision.v1.BoundingPoly box) {
		Polygon polygon = new Polygon();
		
		if (box.getVerticesCount() > 0) {
			for (int i=0; i<box.getVerticesCount(); i++) {
				polygon.addPoint(	Math.max(0, box.getVertices(i).getX()),
									Math.max(0, box.getVertices(i).getY()));
			}
		} 
		else if (box.getNormalizedVerticesCount() > 0) {
			for (int i=0; i<box.getNormalizedVerticesCount(); i++) {
				polygon.addPoint(	Math.max(0, (int)(box.getNormalizedVertices(i).getX() * maxX)),
									Math.max(0, (int)(box.getNormalizedVertices(i).getY() * maxY)));
			}
		}
		
		return polygon;
	}

	/** Google block type to PAGE region type */
	private RegionType getRegionType(BlockType blockType) {
		if (blockType == BlockType.TEXT)
			return RegionType.TextRegion;
		if (blockType == BlockType.PICTURE)
			return RegionType.ImageRegion;
		if (blockType == BlockType.TABLE)
			return RegionType.TableRegion;
		if (blockType == BlockType.RULER)
			return RegionType.SeparatorRegion;
		if (blockType == BlockType.BARCODE)
			return RegionType.GraphicRegion;
		return RegionType.UnknownRegion;
	}
	
	private Page handleObjectAnnotations(List<LocalizedObjectAnnotation> annotations) {
		Page page = new Page();
		
		for (LocalizedObjectAnnotation annotation : annotations) {
			Polygon coords = convertToPolygon(annotation.getBoundingPoly());
			if (coords.getSize() > 2) {
				Region region = page.getLayout().createRegion(RegionType.ImageRegion);
				region.setCoords(coords);
				try {
					region.getAttributes().get("custom").setValue(VariableValue.createValueObject(annotation.getName()));
				} catch (Exception exc) {
					exc.printStackTrace();
				}
			}
		}
		
		return page;
	}

}
