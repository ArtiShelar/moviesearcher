package es.udc.riws.moviesearcher.lucene;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import es.udc.riws.moviesearcher.model.Movie;
import es.udc.riws.moviesearcher.model.Person;
import es.udc.riws.moviesearcher.model.Person.TypePerson;

public class Searcher {

	public static List<Movie> search(String q, String qTitle, String qDescription, Integer qYear, Integer qYearInit,
			Integer qYearEnd, Float qMinVoteAverage, Integer qRuntime, String[] qGenres, String[] qCast,
			String[] qDirectors, Boolean strict) {
		List<Movie> movies = new ArrayList<Movie>();

		Occur ocurr = Occur.SHOULD;
		if (strict != null && strict) {
			ocurr = Occur.MUST;
		}

		// TODO: Configurar Analyzer
		Analyzer analyzer = new StandardAnalyzer(ConstantesLucene.version);
		File folder = new File(ConstantesLucene.directory);
		Directory directory;
		try {
			directory = FSDirectory.open(folder);
			DirectoryReader ireader = DirectoryReader.open(directory);
			IndexSearcher isearcher = new IndexSearcher(ireader);

			// Campos de búsqueda

			BooleanQuery booleanQuery = new BooleanQuery();
			// Búsqueda general
			if (q != null && !q.equals("")) {
				// Todos los campos de búsqueda
				String[] camposBusqueda = new String[] { ConstantesLucene.title, ConstantesLucene.description,
						ConstantesLucene.year, ConstantesLucene.voteAverage, ConstantesLucene.runtime,
						ConstantesLucene.cast, ConstantesLucene.directors, ConstantesLucene.writers,
						ConstantesLucene.genres };

				MultiFieldQueryParser parser = new MultiFieldQueryParser(ConstantesLucene.version, camposBusqueda,
						analyzer);
				booleanQuery.add(parser.parse(q), ocurr);
			}
			if (qTitle != null && !qTitle.equals("")) {
				booleanQuery.add(new TermQuery(new Term(ConstantesLucene.title, qTitle)), ocurr);
			}
			if (qDescription != null && !qDescription.equals("")) {
				booleanQuery.add(new TermQuery(new Term(ConstantesLucene.description, qDescription)), ocurr);
			}

			// TODO: Este campo todavía no se muestra en la interfaz
			if (qYear != null) {
				booleanQuery.add(NumericRangeQuery.newIntRange(ConstantesLucene.year, qYear, qYear, true, true), ocurr);
			}

			qYearInit = qYearInit != null && qYearInit == 0 ? null : qYearInit;
			qYearEnd = qYearEnd != null && qYearEnd == 0 ? null : qYearEnd;
			if (qYearInit != null || qYearEnd != null) {
				booleanQuery.add(NumericRangeQuery.newIntRange(ConstantesLucene.year, qYearInit, qYearEnd, true, true),
						ocurr);
			}

			if (qMinVoteAverage != null && qMinVoteAverage > 0) {
				booleanQuery.add(NumericRangeQuery.newFloatRange(ConstantesLucene.voteAverage, qMinVoteAverage, null,
						true, true), ocurr);
			}

			if (qRuntime != null) {
				// Se aplica una varianza de +-10 minutos
				booleanQuery.add(NumericRangeQuery.newIntRange(ConstantesLucene.runtime, qRuntime - 10, qRuntime + 10,
						true, true), ocurr);
			}

			if (qGenres != null) {
				for (String qGenre : qGenres) {
					booleanQuery.add(new TermQuery(new Term(ConstantesLucene.genres, qGenre)), ocurr);
				}
			}

			if (qCast != null) {
				for (String qActor : qCast) {
					booleanQuery.add(new TermQuery(new Term(ConstantesLucene.cast, qActor)), ocurr);
				}
			}

			if (qDirectors != null) {
				for (String qDirector : qDirectors) {
					booleanQuery.add(new TermQuery(new Term(ConstantesLucene.directors, qDirector)), ocurr);
				}
			}

			// Si no existen condiciones, devolvemos todas las películas
			if (booleanQuery.clauses().isEmpty()) {
				booleanQuery.add(NumericRangeQuery.newFloatRange(ConstantesLucene.voteAverage, 0.1F, null, true, true),
						ocurr);
			}
			TopDocs topdocs = isearcher.search(booleanQuery, null, 1000);

			// Procesamos los resultados
			movies = processResults(topdocs.scoreDocs, isearcher);

			ireader.close();
			directory.close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return movies;
	}

	public static List<Movie> findSimilar(long id) {
		List<Movie> movies = new ArrayList<Movie>();

		Analyzer analyzer = new StandardAnalyzer(ConstantesLucene.version);
		File folder = new File(ConstantesLucene.directory);
		Directory directory;
		try {
			directory = FSDirectory.open(folder);
			DirectoryReader ireader = DirectoryReader.open(directory);
			IndexSearcher isearcher = new IndexSearcher(ireader);

			// Buscamos la película
			Query query = NumericRangeQuery.newLongRange(ConstantesLucene.id, 1, id, id, true, true);
			TopDocs topdoc = isearcher.search(query, 1);
			if (topdoc.scoreDocs == null || topdoc.scoreDocs.length == 0) {
				return movies;
			}
			int docId = topdoc.scoreDocs[0].doc;

			// Similares
			// TODO: Configurar parámetros de similitud. Que es termfreq y
			// docfreq? Habrá problemas con los campos numéricos, como año, y
			// director y casting no sé como está funcionando
			MoreLikeThis mlt = new MoreLikeThis(ireader);
			mlt.setMinTermFreq(1);
			mlt.setMinDocFreq(0);
			// mlt.set
			mlt.setFieldNames(new String[] { ConstantesLucene.genres });
			mlt.setAnalyzer(analyzer);

			Query queryLike = mlt.like(docId);
			TopDocs topdocs = isearcher.search(queryLike, 100);

			// Procesamos los resultados
			movies = processResults(topdocs.scoreDocs, isearcher);
			if (!movies.isEmpty() && movies.get(0).getId().equals(id)) {
				// TODO: Eliminar la película inicial de búsqueda
				// movies.remove(0);
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		System.out.println("Obtenidas " + movies.size() + " películas similares.");

		return movies;
	}

	private static List<Movie> processResults(ScoreDoc[] hits, IndexSearcher isearcher) throws IOException {
		List<Movie> movies = new ArrayList<Movie>();
		for (int i = 0; i < hits.length; i++) {
			Document hitDoc = isearcher.doc(hits[i].doc);

			ScoreDoc score = hits[i];

			// Pasamos el string voteAverage a Float
			String voteAverageString = hitDoc.get(ConstantesLucene.voteAverage);
			Float voteAverage = null;
			if (voteAverageString != null) {
				voteAverage = Float.parseFloat(voteAverageString);
			}

			// Actores
			List<Person> people = new ArrayList<Person>();
			for (String personString : hitDoc.getValues(ConstantesLucene.cast)) {
				String[] campos = personString.split(Pattern.quote(ConstantesLucene.tokenize));
				if (campos != null && campos.length == 3) {
					Integer orden = null;
					if (campos[2] != null && !campos[2].equals("null")) {
						orden = Integer.valueOf(campos[2]);
					}
					people.add(new Person(campos[0], campos[1], orden, TypePerson.CAST));
				}
			}

			// Directores
			for (String personString : hitDoc.getValues(ConstantesLucene.directors)) {
				String[] campos = personString.split(Pattern.quote(ConstantesLucene.tokenize));
				if (campos != null && campos.length > 0) {
					people.add(new Person(campos[0], null, null, TypePerson.DIRECTOR));
				}
			}

			// Escritores
			for (String personString : hitDoc.getValues(ConstantesLucene.writers)) {
				String[] campos = personString.split(Pattern.quote(ConstantesLucene.tokenize));
				if (campos != null && campos.length > 0) {
					people.add(new Person(campos[0], null, null, TypePerson.WRITER));
				}
			}

			// Año
			int year = Integer.valueOf(hitDoc.get(ConstantesLucene.year));

			// Duración
			int runtime = Integer.valueOf(hitDoc.get(ConstantesLucene.runtime));

			// Identificador
			long id = Long.valueOf(hitDoc.get(ConstantesLucene.id));

			// Creamos el objeto película
			Movie movie = new Movie(id, hitDoc.get(ConstantesLucene.title), hitDoc.get(ConstantesLucene.description),
					hitDoc.get(ConstantesLucene.poster), voteAverage, year, runtime, people,
					Arrays.asList(hitDoc.getValues(ConstantesLucene.genres)), score.score);

			movies.add(movie);
		}
		return movies;
	}

}
