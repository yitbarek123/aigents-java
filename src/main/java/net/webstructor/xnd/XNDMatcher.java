/*
 * MIT License
 * 
 * Copyright (c) 2018-2020 by Anton Kolonin, Aigents®
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.webstructor.xnd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;

import net.webstructor.agent.Body;
import net.webstructor.al.AL;
import net.webstructor.al.Iter;
import net.webstructor.al.Reader;
import net.webstructor.al.Seq;
import net.webstructor.al.Time;
import net.webstructor.core.Thing;
import net.webstructor.data.ContentLocator;
import net.webstructor.self.Matcher;
import net.webstructor.util.MapMap;
import net.webstructor.util.Str;

public class XNDMatcher extends Matcher{
	public XNDMatcher(Body body) {
		super(body);	
	}

	@Override
	//match one Pattern for one Thing for one Site
	public int match(String patstr, Iter iter, Thing thing, Date time, String path, ArrayList positions, MapMap thingTexts, MapMap thingPaths, ContentLocator imager, ContentLocator linker, ContentLocator titler) {
		Date now = Time.date(time);
		int matches = 0;
		//TODO:optimization so pattern with properties is not rebuilt every time?
		iter.pos(0);//reset 
		for (;;) {
			Thing instance = new Thing();
			Seq patseq = Reader.pattern(storager,instance, patstr);
			
			StringBuilder summary = new StringBuilder();
			boolean read = readAutoPatterns(iter,patseq,instance,summary);
			if (!read)
				break;
			
			//plain text before "times" and "is" added
			String nl_text = summary.toString();

			//TODO check in mapmap by text now!!!
			//TODO if matched, get the "longer" source path!!!???
			if (thingTexts != null && thingTexts.getObject(thing, nl_text, false) != null)//already adding this
				continue;
 
			instance.addThing(AL.is, thing);
			instance.set(AL.times, now);
			instance.setString(AL.text,nl_text);
			Integer textPos = positions == null ? new Integer(0) : (Integer)positions.get(iter.cur() - 1);
			//try to get title from the structure or generate it from the text
			String title_text = extractTitle(body.filecacher.checkCachedRaw(path));
			if (!AL.empty(title_text))
				instance.setString(AL.title,title_text);
			else
				instance.setString(AL.title, title(path, nl_text, textPos, titler));
			if (imager != null){
				String image = imager.getAvailable(path,textPos);
				if (!AL.empty(image))
					instance.setString(AL.image,image);
			}
			String link = null;
			if (linker != null){
				//measure link pos as link_pos = (link_beg+link_end)/2
				//associate link with text if (text_pos - link_pos) < text_legth/2, where text_pos = (text_beg - text_end)/2
				int range = nl_text.length()/2;
				int text_pos = textPos.intValue() - range;//compute position of text as its middle
				link = linker.getAvailableInRange(path,new Integer(text_pos),range);
			}
			if (thingTexts != null)
				thingTexts.putObject(thing, nl_text, instance);
			if (thingPaths != null)
				thingPaths.putObjects(thing, path == null ? "" : path, instance);
			
			matches++;
		}
		return matches;
	}

	//match all Patterns of one Thing for one Site and send updates to subscribed Peers
	//TODO: Siter extends Matcher (MapMap thingTexts, MapMap thingPaths, Imager imager, Imager linker)
	@Override
	public int match(Iter iter,ArrayList positions,Thing thing,Date time,String path, MapMap thingTexts, MapMap thingPaths, ContentLocator imager, ContentLocator linker, ContentLocator titler) {
		//TODO: re-use iter building it one step above
		//ArrayList positions = new ArrayList();
		//Iter iter = new Iter(Parser.parse(text,positions));//build with original text positions preserved for image matching
		int matches = 0;
		boolean is_article = isMeta(body.filecacher.checkCachedRaw(path), "og:type", "article");
		System.out.println("IS ->" + path + "<- ARTICLE? => " + is_article);
		if (!is_article)
			return 0;
		
		//first, try to get patterns for the thing
		Collection patterns = (Collection)thing.get(AL.patterns);
		
		//next, if none, create the pattern for the thing name manually
		if (AL.empty(patterns))
			//auto-pattern from thing name split apart
			matches += match(thing.getName(),iter,thing,time,path,positions, thingTexts, thingPaths, imager, linker, titler);
		if (!AL.empty(patterns)) {
			for (Iterator it = patterns.iterator(); it.hasNext();){		
		        matches += match(((Thing)it.next()).getName(),iter,thing,time,path,positions, thingTexts, thingPaths, imager, linker, titler);
			}
		}
		return matches;
	}
	
	static boolean isMeta(String source, String property, String content) {
    	String ptoken = "property=\"";
        String ctoken = "content=\"";
        int mbpos = source.indexOf("<meta");
        int mepos = source.indexOf("/>", mbpos);
        while(mbpos != -1 && mepos != -1) {
            String fmc = source.substring(mbpos, mepos);
            int mpbpos = fmc.indexOf(ptoken);
            int mpepos = fmc.indexOf("\"", mpbpos+ptoken.length());
            int mcbpos = fmc.indexOf(ctoken);
            int mcepos = fmc.indexOf("\"", mcbpos+ctoken.length());
            if(mpbpos != -1 && mpepos != -1 && mcbpos != -1 && mcepos != -1) {
                if (fmc.substring(mpbpos+ptoken.length(), mpepos).equalsIgnoreCase(property) &&
                		fmc.substring(mcbpos+ctoken.length(), mcepos).equalsIgnoreCase(content))
                	return true;
            }
            mbpos = source.indexOf("<meta", mepos+1);
            mepos = source.indexOf("/>", mbpos);
        }
        return false;
    }
	
	public String title(String path, String nl_text, int pos, ContentLocator titler) {
		if (titler == null)
			return shortTitle(nl_text);
		String title_text = titler.getAvailableUp(path,0);
		String header_text = titler.getAvailableUp(path,pos);
		if (AL.empty(title_text) && AL.empty(header_text))
			return shortTitle(nl_text);
		if (!AL.empty(title_text) && !AL.empty(header_text)) {
			if (title_text.contentEquals(header_text))
				return title_text;
			double t = Str.simpleTokenizedProximity(nl_text,title_text,AL.punctuation+AL.spaces);
			double h = Str.simpleTokenizedProximity(nl_text,header_text,AL.punctuation+AL.spaces);
			return h > t ? header_text : title_text;
		}
		return AL.empty(header_text) ? title_text : header_text;
	}
	
	public String shortTitle(String text) {
		if(text.matches("(?![0-9]).*["+AL.punctuation+"](?![0-9]).*")) {
			String[] tokens = text.split("["+AL.punctuation+"]");
			for(String s : tokens) {
				while(s.endsWith(" "))
					s = s.substring(0, s.length()-1);
				while(s.startsWith(" "))
					s = s.substring(1, s.length());
				if(s.contains(" "))
					return s;
			}
		}
		return text;
	}
	
	/**
     * @param source - string containing html
     * @param btag - the name of the tag to look for. eg 'html' instead of '<html>'
     * @param pos - starting position of search.
     * @return string containing the content of the first tag found. If no tag found, return null
    */
    static ArrayList<String> getTagContent(String source, String tag) {
        ArrayList<String> tagCont = new ArrayList<String>();
        String ftagr = "<\\s*" + tag + "[^>]*>(.*?)<\\s*/\\s*" + tag + ">";
        String exttagr = "<\\s*[a-z]+[^>]*>(.*?)<\\s*/\\s*[a-z]+>";
        String btagr = "<\\s*" + tag + "[^>]*>(.*?)";
        String etagr = "<\\s*/\\s*" + tag + ">";
        java.util.regex.Pattern ftagc = java.util.regex.Pattern.compile(ftagr);
        java.util.regex.Matcher ftagcm = ftagc.matcher(source);
        while(ftagcm.find()) {
			String fin = ftagcm.group().replaceAll(btagr, "").replaceAll(etagr, "").replaceAll(exttagr, "");
			tagCont.add(fin);
        }
        return tagCont;
    }

    static ArrayList<String> getMetaContByProp(String source, String property) {
        ArrayList<String> mCont = new ArrayList<String>();
        String ptoken = "property=\"";
        String ctoken = "content=\"";
        int mbpos = source.indexOf("<meta");
        int mepos = source.indexOf(">", mbpos);
        while(mbpos != -1 && mepos != -1) {
            String fmc = source.substring(mbpos, mepos);
            int mpbpos = fmc.indexOf(ptoken);
            int mpepos = fmc.indexOf("\"", mpbpos+ptoken.length());
            if(mpbpos != -1 && mpepos != -1) {
                if (fmc.substring(mpbpos+ptoken.length(), mpepos).equalsIgnoreCase(property)) {
                    int mcbpos = fmc.indexOf(ctoken);
                    int mcepos = fmc.indexOf("\"", mcbpos+ctoken.length());
                    if(mcbpos != -1 && mcepos != -1)
                        mCont.add(fmc.substring(mcbpos+ctoken.length(), mcepos));
                }
            }
            mbpos = source.indexOf("<meta", mepos+1);
            mepos = source.indexOf(">", mbpos);
        }
        return mCont;
    }

    /**
     * @param source - string containing html
     * @return string, title for the page
    */
    static String extractTitle(String source) {
        StringBuilder sb = new StringBuilder();
        ArrayList<String> tTagC = getTagContent(source, "title");
        ArrayList<String> hTagC = getTagContent(source, "h[1-6]");
        ArrayList<String> mTitle = getMetaContByProp(source, "og:title");
        String tit = "" , htit = "", mtit = "";
        if (mTitle.size() != 0)
            mtit = mTitle.get(0);
        if (tTagC.size() != 0) {
            tit = tTagC.get(0);
            sb = new StringBuilder();
            //addText(sb, tit);
            tit = sb.toString();
        }
        for (String h1 : hTagC) {
            //addText(sb, h1);
            htit = sb.toString();
            if (Str.levenshteinDistance(htit, tit) > 0.75 || Str.levenshteinDistance(htit, mtit) > 0.75)
                return htit;
        }
        if (Str.levenshteinDistance(mtit, tit) > 0.75)
            return mtit;

        return tit;
    }
}