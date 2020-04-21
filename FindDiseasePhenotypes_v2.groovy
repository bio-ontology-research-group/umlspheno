@Grab(group='org.apache.lucene', module='lucene-core', version='4.7.0')
@Grab(group='org.apache.lucene', module='lucene-analyzers-common', version='4.7.0')
@Grab(group='org.apache.lucene', module='lucene-queryparser', version='4.7.0')
@Grab(group='org.semanticweb.elk', module='elk-owlapi', version='0.4.3')
@Grab(group='net.sourceforge.owlapi', module='owlapi-api', version='4.2.5')
@Grab(group='net.sourceforge.owlapi', module='owlapi-apibinding', version='4.2.5')
@Grab(group='net.sourceforge.owlapi', module='owlapi-impl', version='4.2.5')
@Grab(group='net.sourceforge.owlapi', module='owlapi-parsers', version='4.2.5')


import org.apache.lucene.analysis.*
import org.apache.lucene.analysis.standard.*
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.store.*
import org.apache.lucene.util.*
import org.apache.lucene.search.*
import org.apache.lucene.queryparser.classic.*
import org.apache.lucene.search.highlight.*
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.reasoner.*
import org.semanticweb.owlapi.profiles.*
import org.semanticweb.owlapi.util.*
import org.semanticweb.elk.owlapi.*
import groovy.json.*
import org.semanticweb.owlapi.search.*;
import org.semanticweb.owlapi.model.parameters.*
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerConfiguration
import org.semanticweb.elk.reasoner.config.*
import org.semanticweb.owlapi.reasoner.structural.StructuralReasoner
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.io.*;
import org.semanticweb.owlapi.owllink.*;
import org.semanticweb.owlapi.util.*;
import org.semanticweb.owlapi.manchestersyntax.renderer.*;



def fout = new PrintWriter(new BufferedWriter(new FileWriter(args[0])))

Double npmi(Double total, Double x, Double y, Double xy) {
  Double px = x/total
  Double py = y/total
  Double pxy = xy/total
  Double pmi = Math.log(pxy/(px*py))
  Double npmi = pmi/(-1 * Math.log(pxy))
  return npmi
}

Double tscore(Double total, Double x, Double y, Double xy) {
  return (xy - (x * y / (total * total))) / Math.sqrt(xy)
}

Double zscore(Double total, Double x, Double y, Double xy) {
  return (xy - (x * y / (total * total))) / Math.sqrt(x*y/(total * total))
}

Double lmi(Double total, Double x, Double y, Double xy) {
  return xy * Math.log(total * xy / (x * y))
}

Double lgl(Double total, Double x, Double y, Double xy) {
  def lambda = total * Math.log(total) - x * Math.log(x) - y * Math.log(y) + xy * Math.log(xy) + (total - x -y + xy)*Math.log(total - x -y + xy) + (x - xy) * Math.log(x-xy) + (y - xy) * Math.log(y-xy) - (total - x) * Math.log(total-x) - (total-y) * Math.log(total - y)

  return xy < (x*y/total) ? -2*Math.log(lambda) : 2*Math.log(lambda)
}



def jsonslurper = new JsonSlurper()

String indexPath = "/home/kafkass/mondo_pheno/lucene-medline-2019/"

Directory dir = FSDirectory.open(new File(indexPath)) 
Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_47)

DirectoryReader reader = DirectoryReader.open(dir)
IndexSearcher searcher = new IndexSearcher(reader)

QueryParser parser = new QueryParser(Version.LUCENE_47, "text", analyzer)
QueryBuilder builder = new QueryBuilder(analyzer)

Map<String, Set<String>> id2super = [:]
Map<String, Set<String>> id2sub = [:]
Map<String, Set<String>> name2id = [:].withDefault { new TreeSet() }
Map<String, Set<String>> id2name = [:].withDefault { new TreeSet() }
Map<String, Set<String>> id2pmid = [:].withDefault { new TreeSet() }

def id = ""
OWLOntologyManager manager = OWLManager.createOWLOntologyManager()  
OWLDataFactory fac = manager.getOWLDataFactory()

 def parseOntologies = { ontUriOrfile -> 

  ontologyfile = manager.loadOntologyFromOntologyDocument(ontUriOrfile)

  ontologyfile.getClassesInSignature(true).each {
   cl ->
    class_ = cl.toString()
    id = class_.substring(class_.lastIndexOf('/')+1,class_.length()-1).replaceAll('_',':')
    EntitySearcher.getAnnotationObjects(cl, ontologyfile, fac.getRDFSLabel()).each {
     lab ->
      if (lab.getValue() instanceof OWLLiteral) {
       def labs = (OWLLiteral) lab.getValue()
       name = labs.getLiteral().trim().toLowerCase()
       if (name2id[name] == null) {
          name2id[name] = new TreeSet()
        }
       name2id[name].add(id)
       id2name[id].add(name)
      }
    }

    EntitySearcher.getAnnotationAssertionAxioms(cl, ontologyfile).each {
      ax ->
       if (ax.getProperty().toString() == "<http://www.geneontology.org/formats/oboInOwl#hasDbXref>") {
          if (ax.getValue()!= null && ax.getValue()!= [] && !(ax.getValue() instanceof IRI)){
            def syn = ax.getValue()
            if(!(ax.getValue() instanceof String) ){
              syn = ax.getValue().getLiteral()
            }
            if (name2id[syn] == null) {
              name2id[syn] = new TreeSet()
            }
            name2id[syn].add(id)
            id2name[id].add(syn)    
          }
          
        }else if(ax.getProperty().toString() == "<http://www.geneontology.org/formats/oboInOwl#hasExactSynonym>"){
          if (ax.getValue()!= null && ax.getValue()!= [] && !(ax.getValue() instanceof IRI)){
            def syn = ax.getValue()
            if(!(ax.getValue() instanceof String) ){
              syn = ax.getValue().getLiteral()
            }
            if (name2id[syn] == null) {
              name2id[syn] = new TreeSet()
            }
            name2id[syn].add(id)
            id2name[id].add(syn)
          }
        }
    }
  }

println "finished with par1 of parsing\n"
  def factory = fac
  
  def ont = ontologyfile
  
  OWLReasonerFactory reasonerFactory = null
  
  //  ConsoleProgressMonitor progressMonitor = new ConsoleProgressMonitor()
  //  OWLReasonerConfiguration config = new SimpleConfiguration(progressMonitor)
  
  OWLReasonerFactory f1 = new ElkReasonerFactory()
  OWLReasoner reasoner = f1.createReasoner(ont)
  
  reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY)
  
  ont.getClassesInSignature().each { cl ->
    def clst = cl.toString().replaceAll("<http://purl.obolibrary.org/obo/","").replaceAll("<http://phenomebrowser.net/","").replaceAll(">","").replaceAll("_",":")
    if (id2sub[clst] == null) {
      id2sub[clst] = new TreeSet()
    }
    //    id2super[clst].add(clst)
    reasoner.getSubClasses(cl, false).getFlattened().each { sup ->
      def supst = sup.toString().replaceAll("<http://purl.obolibrary.org/obo/","").replaceAll("<http://phenomebrowser.net/","").replaceAll(">","").replaceAll("_",":")
      
      
      id2sub[clst].add(supst)
    }
  }
}

parseOntologies(IRI.create("http://purl.obolibrary.org/obo/mp.owl"))
parseOntologies(new File("./icdomim.owl"))
parseOntologies(IRI.create("http://purl.obolibrary.org/obo/hp.owl"))

BooleanQuery.setMaxClauseCount(100000000)
id2name.each { k, v ->
  //  id2sub[k]?.each { s.add(it) }
  if (k.indexOf("HP:")>-1 ||  k.indexOf("MP:")>-1 || k.indexOf("umls:")>-1) {
  BooleanQuery query = new BooleanQuery()
  v.each { name ->
    try {
      Query q = builder.createPhraseQuery(args[1], name)
      query.add(q, BooleanClause.Occur.SHOULD)
      q = builder.createPhraseQuery("title", name)
      query.add(q, BooleanClause.Occur.SHOULD)
    } catch (Exception E) {}
  }
  println "Querying $k ($query)..."
  ScoreDoc[] hits = null
  try {
    hits = searcher.search(query, null, 32768, Sort.RELEVANCE, true, true).scoreDocs
  } catch (Exception E) {E.printStackTrace()}
  hits?.each { doc ->
    Document hitDoc = searcher.doc(doc.doc)
    def pmid = hitDoc.get("pmid")
    if (pmid) {
      id2pmid[k].add(pmid)
    }
  }
}
}

println "adding subclasses..."
def id2pmidClosed = [:]
id2pmid.each { k, v ->
  Set s = new TreeSet(v)
  id2sub[k].each { sub ->
    if (sub in id2pmid.keySet()) {
      s.addAll(id2pmid[sub])
    }
  }
  id2pmidClosed[k] = s
}
id2pmid = id2pmidClosed


Set tempSet = new LinkedHashSet()
id2pmid.each { k, v ->
  tempSet.addAll(v)
}
def corpussize = tempSet.size()

println "Corpussize $corpussize..."
//FIXME: remove
//System.exit(0)

println "Indexing PMIDs..."
def indexPMID = [:]
def count = 0
tempSet.each { pmid ->
  indexPMID[pmid] = count
  count += 1
}

println "Generating BitSets..."
Map<String, Set<String>> bsid2pmid = [:]
id2pmid.each { k, v ->
  OpenBitSet bs = new OpenBitSet(corpussize)
  v.each { pmid ->
    bs.set(indexPMID[pmid])
  }
  bsid2pmid[k] = bs
}

bsid2pmid.findAll { k, v -> k.indexOf("umls")>-1 }.each { doid, pmids1 ->
  println "Computing on $doid..."
  bsid2pmid.findAll { k, v -> (k.indexOf("HP")>-1 || k.indexOf("MP")>-1 ) }.each { pid, pmids2 ->
    def nab = OpenBitSet.intersectionCount(pmids1, pmids2)
    //    if (nab > 0 ) {
      def na = pmids1.cardinality()
      def nb = pmids2.cardinality()
      def tscore = tscore(corpussize, na, nb, nab)
      def pmi = npmi(corpussize, na, nb, nab)
      def zscore = zscore(corpussize, na, nb, nab)
      def lmi = lmi(corpussize, na, nb, nab)
      def lgl = lgl(corpussize, na, nb, nab)
      def name1 = id2name[doid]
      def name2 = id2name[pid]
      fout.println("$doid\t$pid\t$tscore\t$zscore\t$lmi\t$pmi\t$lgl\t$nab\t$na\t$nb\t$name1\t$name2")
    }
  //  }
}

fout.flush()
fout.close()
