/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.console.command;

import java.io.IOException;
import java.io.OutputStream;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.rdf4j.common.iteration.Iterations;

import org.eclipse.rdf4j.console.ConsoleIO;
import org.eclipse.rdf4j.console.ConsoleParameters;
import org.eclipse.rdf4j.console.ConsoleState;
import org.eclipse.rdf4j.console.setting.ConsoleSetting;
import org.eclipse.rdf4j.console.setting.ConsoleWidth;
import org.eclipse.rdf4j.console.setting.QueryPrefix;
import org.eclipse.rdf4j.console.setting.WorkDir;
import org.eclipse.rdf4j.console.util.ConsoleQueryResultWriter;
import org.eclipse.rdf4j.console.util.ConsoleRDFWriter;

import org.eclipse.rdf4j.model.Namespace;

import org.eclipse.rdf4j.query.MalformedQueryException;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryInterruptedException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.UnsupportedQueryLanguageException;
import org.eclipse.rdf4j.query.UpdateExecutionException;
import org.eclipse.rdf4j.query.parser.ParsedBooleanQuery;
import org.eclipse.rdf4j.query.parser.ParsedGraphQuery;
import org.eclipse.rdf4j.query.parser.ParsedOperation;
import org.eclipse.rdf4j.query.parser.ParsedTupleQuery;
import org.eclipse.rdf4j.query.parser.ParsedUpdate;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.query.resultio.QueryResultFormat;
import org.eclipse.rdf4j.query.resultio.QueryResultIO;
import org.eclipse.rdf4j.query.resultio.QueryResultWriter;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract query evaluator command
 * 
 * @author Dale Visser
 * @author Bart Hanssens
 */
public abstract class QueryEvaluator extends ConsoleCommand {

	private static final Logger LOGGER = LoggerFactory.getLogger(QueryEvaluator.class);

	private final Map<String,ConsoleSetting> settings;
	private final TupleAndGraphQueryEvaluator evaluator;

	private final List<String> sparqlQueryStart = Arrays.asList(
								new String[]{"select", "construct", "describe", "ask", "prefix", "base"});
	
	private final long MAX_INPUT = 1_000_000;
	private final static Pattern P_INFILE = Pattern.compile("^INFILE=(\"[^\"]+\")(,[\\w-]+)?");
	private final static Pattern P_OUTFILE = Pattern.compile("^");
	
	// [INFILE="input file"[,enc]] [OUTPUT="out/file"[,enc]]
	private final static Pattern PATTERN_IO = 
			Pattern.compile("^('in'INFILE=('i'\"[^\"]+\")('ic',\\w[\\w-]+)?)? "
							+ "('out'OUTFILE=('o'\"[^\"]+\")");
	
	
	/**
	 * Constructor
	 * 
	 * @param consoleIO
	 * @param state
	 * @param parameters 
	 */
	@Deprecated
	public QueryEvaluator(ConsoleIO consoleIO, ConsoleState state, ConsoleParameters parameters) {
		super(consoleIO, state);
		this.settings = convertParams(parameters);
		this.evaluator = new TupleAndGraphQueryEvaluator(consoleIO, state, parameters);
	}

	/**
	 * Constructor
	 * 
	 * @param evaluator
	 * @param settings 
	 */
	public QueryEvaluator(TupleAndGraphQueryEvaluator evaluator, Map<String,ConsoleSetting> settings) {
		super(evaluator.getConsoleIO(), evaluator.getConsoleState());
		this.settings = settings;
		this.evaluator = evaluator;
	}
	
	/**
	 * Check if query string already contains query prefixes
	 * 
	 * @param query query string
	 * @return true if namespaces are already used 
	 */
	protected abstract boolean hasQueryPrefixes(String query);
		
	/**
	 * Add namespace prefixes to query
	 * 
	 * @param result 
	 * @param namespaces collection of known namespaces
	 */
	protected abstract void addQueryPrefixes(StringBuffer result, Collection<Namespace> namespaces);

	/**
	 * Get console width setting
	 * Use a new console width setting when not found.
	 * 
	 * @return boolean
	 */
	private int getConsoleWidth() {
		return ((ConsoleWidth) settings.getOrDefault(ConsoleWidth.NAME, new ConsoleWidth())).get();
	}

	/**
	 * Get show prefix setting
	 * Use a new show prefix setting when not found.
	 * 
	 * @return boolean
	 */
	private boolean getQueryPrefix() {
		return ((QueryPrefix) settings.getOrDefault(QueryPrefix.NAME, new QueryPrefix())).get();
	}

	/**
	 * Get working dir setting
	 * Use a working dir setting when not found.
	 * 
	 * @return path of working dir
	 */
	private Path getWorkDir() {
		return ((WorkDir) settings.getOrDefault(WorkDir.NAME, new WorkDir())).get();
	}

	/**
	 * Execute a SPARQL or SERQL query, defaults to SPARQL
	 * 
	 * @param command to execute
	 * @param operation "sparql", "serql", "base" or SPARQL query form
	 */
	public void executeQuery(final String command, final String operation) {
		Repository repository = state.getRepository();
		if (repository == null) {
			consoleIO.writeUnopenedError();
			return;
		}
		
		if (sparqlQueryStart.contains(operation)) {
			parseAndEvaluateQuery(QueryLanguage.SPARQL, command);
		} else if ("serql".equals(operation)) {
			parseAndEvaluateQuery(QueryLanguage.SERQL, command.substring("serql".length()));
		} else if ("sparql".equals(operation)) {
			parseAndEvaluateQuery(QueryLanguage.SPARQL, command.substring("sparql".length()));
		} else {
			consoleIO.writeError("Unknown command");
		}
	}

	/**
	 * Read query string from a file.
	 * Optionally a character set can be specified, otherwise UTF-8 will be used.
	 *
	 * @param filename file name
	 * @param cset character set name or null
	 * @return query from file as string
	 * @throws IllegalArgumentException when character set was not recognized
	 * @throws IOException when input file could not be read
	 */
	private String readFile(String filename, String cset) throws IllegalArgumentException, IOException {
		if (filename == null || filename.isEmpty()) {
			throw new IllegalArgumentException("Empty file name");
		}
		Charset charset = (cset == null || cset.isEmpty()) ? StandardCharsets.UTF_8 : Charset.forName(cset);
		
		Path p = Paths.get(filename);
		if (!p.isAbsolute()) {
			p = getWorkDir().resolve(p);
		}
		if (p.toFile().canRead()) {
			throw new IOException("Cannot read file " + p);
		}
		// limit file size
		if (p.toFile().length() > MAX_INPUT) {
			throw new IOException("File larger than " + MAX_INPUT + " bytes");
		}
		byte[] bytes = Files.readAllBytes(p);
		return new String(bytes, charset);
	}

	/**
	 * Get absolute path to output file, using working directory for relative file name.
	 * Verifies that the file doesn't exist or can be overwritten if it does exist.
	 * 
	 * @param filename file name
	 * @return path absolute path
	 * @throws IllegalArgumentException
	 * @throws IOException
	 */
	private Path getPathForOutput(String filename) throws IllegalArgumentException, IOException {
		if (filename == null || filename.isEmpty()) {
			throw new IllegalArgumentException("Empty file name");
		}

		Path p = Paths.get(filename);
		if (! p.isAbsolute()) {
			p = getWorkDir().resolve(filename);
		}
		
		if (!p.toFile().exists() || consoleIO.askProceed("File " + p + " exists", false)) {
			return p;
		}
		throw new IOException("Could not open file for output");
	}
	
	/**
	 * Read (possibly multi-line) query.
	 * Returns multi-line query as one string, or the original string if query is not multi-line.
	 * 
	 * @param queryLn query language
	 * @param queryText query string
	 * @return query or null
	 */
	private String readMultiline(QueryLanguage queryLn, String queryText) {
		String str = queryText.trim(); 
		if (!str.isEmpty()) {
			return str;
		}
		try {
			consoleIO.writeln("enter multi-line " + queryLn.getName() + " query "
							+ "(terminate with line containing single '.')");
			return consoleIO.readMultiLineInput();
		} catch (IOException e) {
			consoleIO.writeError("I/O error: " + e.getMessage());
			LOGGER.error("Failed to read query", e);
		}
		return null;
	}
	
	/**
	 * Parse and evaluate a SERQL or SPARQL query.
	 * Check if query is multi-line or to be read from input file, 
	 * and check if results are to be written to an output file.
	 * 
	 * @param queryLn query language
	 * @param queryText query string
	 */
	private void parseAndEvaluateQuery(QueryLanguage queryLn, String queryText) {
		String str = readMultiline(queryLn, queryText);
		if (str == null || str.isEmpty()) {
			consoleIO.writeError("Empty query string");
			return;
		}

		Path path = null;
		
		// check if input and/or output file are specified
		Matcher m = PATTERN_IO.matcher(str);
		if (m.matches()) {
			try {
				// check for output file first
				String outfile = m.group("o");
				if (outfile != null && !outfile.isEmpty()) {
					path = getPathForOutput(outfile);
					str = str.substring(m.group(0).length()); // strip both INPUT/OUTPUT from query
				}
				String infile = m.group("i");
				if (infile != null && !infile.isEmpty()) {
					str = readFile(infile, m.group("ic")); // ignore remainder of command line query
				}
			} catch (IOException|IllegalArgumentException ex) {
				consoleIO.writeError(ex.getMessage());
				return;
			}
		}
		
		// add namespace prefixes
		String queryString = addRepositoryQueryPrefixes(str);

		try {
			ParsedOperation query = QueryParserUtil.parseOperation(queryLn, queryString, null);
			evaluateQuery(queryLn, query, path);
		} catch (UnsupportedQueryLanguageException e) {
			consoleIO.writeError("Unsupported query language: " + queryLn.getName());
		} catch (MalformedQueryException e) {
			consoleIO.writeError("Malformed query: " + e.getMessage());
		} catch (QueryInterruptedException e) {
			consoleIO.writeError("Query interrupted: " + e.getMessage());
			LOGGER.error("Query interrupted", e);
		} catch (QueryEvaluationException e) {
			consoleIO.writeError("Query evaluation error: " + e.getMessage());
			LOGGER.error("Query evaluation error", e);
		} catch (RepositoryException e) {
			consoleIO.writeError("Failed to evaluate query: " + e.getMessage());
			LOGGER.error("Failed to evaluate query", e);
		} catch (UpdateExecutionException e) {
			consoleIO.writeError("Failed to execute update: " + e.getMessage());
			LOGGER.error("Failed to execute update", e);
		}
	}

	/**
	 * Get a query result writer based upon the file name (extension),
	 * or return the console result writer when path is null.
	 * 
	 * @param path path or null
	 * @param out output stream or null
	 * @return result writer
	 * @throws IllegalArgumentException
	 */
	private QueryResultWriter getQueryResultWriter(Path path, OutputStream out) 
																		throws IllegalArgumentException {
		if (path == null) {
			return new ConsoleQueryResultWriter(consoleIO, getConsoleWidth());
		}
		Optional<QueryResultFormat> fmt = QueryResultIO.getWriterFormatForFileName(path.toFile().toString());
		if (!fmt.isPresent()) {
			throw new IllegalArgumentException("No suitable result writer found");
		}
		return QueryResultIO.createWriter(fmt.get(), out);	
	}

	/**
	 * Get a graph result (RIO) writer based upon the file name (extension),
	 * or return the console result writer when path is null.
	 * 
	 * @param path path or null
	 * @param out output stream or null
	 * @return result writer
	 * @throws IllegalArgumentException
	 */
	private RDFWriter getRDFWriter(Path path, OutputStream out) throws IllegalArgumentException {
		if (path == null) {
			return new ConsoleRDFWriter(consoleIO, getConsoleWidth());
		}
		Optional<RDFFormat> fmt = Rio.getWriterFormatForFileName(path.toFile().toString());
		if (!fmt.isPresent()) {
			throw new IllegalArgumentException("No suitable result writer found");
		}
		return Rio.createWriter(fmt.get(), out);	
	}
	
	/**
	 * Get output stream for a file, or for the console output if path is null
	 * 
	 * @param path file path or null
	 * @return file or console outputstream
	 * @throws IOException
	 */
	private OutputStream getOutputStream(Path path) throws IOException {
		return (path != null) 
					? Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, 
													StandardOpenOption.TRUNCATE_EXISTING,
													StandardOpenOption.WRITE)
					: consoleIO.getOutputStream();
	}

			
	/**
	 * Evaluate a SPARQL or SERQL query that has already been parsed
	 * 
	 * @param queryLn query language
	 * @param query parsed query
	 * @param path
	 * @throws MalformedQueryException
	 * @throws QueryEvaluationException
	 * @throws RepositoryException
	 * @throws UpdateExecutionException 
	 */
	private void evaluateQuery(QueryLanguage queryLn, ParsedOperation query, Path path)
			throws MalformedQueryException, QueryEvaluationException,  UpdateExecutionException {

		String queryString = query.getSourceString();

		try(OutputStream os = getOutputStream(path)) {
			if (query instanceof ParsedTupleQuery) {
				QueryResultWriter writer = getQueryResultWriter(path, os);
				evaluator.evaluateTupleQuery(queryLn, queryString, writer);
			} else if (query instanceof ParsedBooleanQuery) {
				QueryResultWriter writer = getQueryResultWriter(path, os);
				evaluator.evaluateBooleanQuery(queryLn, queryString, writer);
			} if (query instanceof ParsedGraphQuery) {
				RDFWriter writer = getRDFWriter(path, os);
				evaluator.evaluateGraphQuery(queryLn, queryString, writer);
			} else if (query instanceof ParsedUpdate) {
				// no outputstream for updates, can only be console output
				if (path != null) {
					throw new IllegalArgumentException("Update query does not produce output");
				}
				evaluator.executeUpdate(queryLn, queryString);
			} else {
				consoleIO.writeError("Unexpected query type");
			}
		} catch (IllegalArgumentException | IOException ioe) {
			consoleIO.writeError(ioe.getMessage());
		}
	}

	/**
	 * Add namespaces prefixes to SPARQL or SERQL query from repository connection
	 * 
	 * @param queryString query string
	 * @return query string with prefixes
	 */
	private String addRepositoryQueryPrefixes(String queryString) {
		StringBuffer result = new StringBuffer(queryString.length() + 512);
		result.append(queryString);
		
		String upperCaseQuery = queryString.toUpperCase(Locale.ENGLISH);
		Repository repository = state.getRepository();
			
		if (repository != null && getQueryPrefix() && !hasQueryPrefixes(upperCaseQuery)) {
			// FIXME this is a bit of a sloppy hack, a better way would be to
			// explicitly provide the query parser with name space mappings in
			// advance.
			try {
				try (RepositoryConnection con = repository.getConnection()) {
					Collection<Namespace> namespaces = Iterations.asList(con.getNamespaces());
					if (!namespaces.isEmpty()) {
						addQueryPrefixes(result, namespaces);
					}
				}
			} catch (RepositoryException e) {
				consoleIO.writeError("Error connecting to repository: " + e.getMessage());
				LOGGER.error("Error connecting to repository", e);
			}
		}
		return result.toString();
	}
}
