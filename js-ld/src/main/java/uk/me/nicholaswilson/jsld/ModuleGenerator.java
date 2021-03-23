package uk.me.nicholaswilson.jsld;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.shapesecurity.functional.data.ImmutableList;
import com.shapesecurity.functional.data.Maybe;
import com.shapesecurity.shift.ast.*;
import com.shapesecurity.shift.scope.GlobalScope;
import com.shapesecurity.shift.scope.ScopeAnalyzer;
import com.shapesecurity.shift.scope.Variable;
import uk.me.nicholaswilson.jsld.SymbolTable.MemoryDefinition;
import uk.me.nicholaswilson.jsld.wasm.WasmLimits;
import uk.me.nicholaswilson.jsld.wasm.WasmObjectType;

class ModuleGenerator {

  // For good hygiene, I'm using double-underscore for anything that might
  // collide with names used by customer code.  For example, someone could
  // import an external module called "jQuery", or maybe there's some wildly
  // popular third-party script called "symbols"...  Thus I'm trying to keep to
  // an absolute minimum any pollution of the namespace that modules are nested
  // inside, and use underscores for those symbols to make sure.
  public static final String ROOT_VAR = dontMungeSymbol("__root");
  public static final String FETCHER_VAR = mungeSymbol("__fetcher");
  public static final String EXPORTS_VAR = dontMungeSymbol("__exports");
  public static final String SYMBOLS_VAR = mungeSymbol("__symbols");
  public static final String LATE_BINDER_VAR = mungeSymbol("__lateBind");

  private List<Statement> scriptStatements = new ArrayList<>();
  private final List<SymbolsFile> symbolsFiles;
  private final List<ExportsFile> exportsFiles;
  private final WasmFile wasmFile;
  private final List<MemoryDefinition> memoryDefinitions;
  private final String moduleName;
  private final Set<String> externs;

  public ModuleGenerator(
    List<SymbolsFile> symbolsFiles,
    List<ExportsFile> exportsFiles,
    WasmFile wasmFile,
    List<MemoryDefinition> memoryDefinitions,
    String moduleName,
    Set<String> externs
  ) {
    this.symbolsFiles = symbolsFiles;
    this.exportsFiles = exportsFiles;
    this.wasmFile = wasmFile;
    this.memoryDefinitions = memoryDefinitions;
    this.moduleName = moduleName;
    this.externs = externs;
  }

  public Script generate() {
    generatePreamble();
    generateJsSymbols();
    generateJsExports();
    generatePostamble();
    generateWrapper();
    Script script = generateScript();
    analyzeExterns(script);
    return script;
  }

  private void analyzeExterns(Script script) {
    // Run the scope analyzer to detect for any dodgy use of global variables
    // that shouldn't be allowed (eg a global variable leak).
    GlobalScope scope = ScopeAnalyzer.analyze(script);
    List<Variable> bannedVariables = scope.variables().stream()
      .filter(v -> !externs.contains(v.name))
      .collect(Collectors.toList());

    if (!bannedVariables.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (Variable variable : bannedVariables) {
        sb.append("\n  ").append(variable.name);
      }
      throw new LdException(
        "Error - module contains unbound variables:" + sb.toString()
      );
    }
  }


  private void generatePreamble() {
    ModuleUtil.appendFragment(
      scriptStatements,
      "const " + EXPORTS_VAR + " = {};" +
        "const " + SYMBOLS_VAR + " = {};"
    );

    // Hah, this needs some explanation!  Modules in general have circular
    // dependencies (eg. Wasm has to import some symbols from JS, and JS has to
    // import from Wasm).  Modules could also import symbols from each other in
    // a loop - which is completely fine, we just have to bind the symbols in
    // one pass, before any of them are actually used.  To break the circle, I'm
    // using a fancy trick with Proxy objects.  The idea is to use a proxy as
    // the initial value for imported symbols, and rebind the variable to the
    // correct definition after all modules have provided their exports!  We do
    // this using the __lateBind helper, which builds the proxy.  It should take
    // a binder function, which lazily looks up the symbol (and as a cute
    // optimisation, rebinds the symbol too so the proxy is only used for the
    // initial call!).
    ModuleUtil.appendFragment(
      scriptStatements,
      "function " + LATE_BINDER_VAR + "(binder, isCallable) {" +
        // The fakeTarget won't ever by used for anything; it's replaced by the
        // real target below.  However, bizarrely and sadly, we can't just use
        // "{}" as the fake target - it needs to be a callable since the proxy's
        // IsCallable slot immutably copies the value of the target, rather than
        // allowing it to be forwarded via a proxy operation :(
        "  const fakeTarget = isCallable ? (function(){}) : {};" +
        "  const reflectingHandler = new Proxy({}, {" +
        "    get(reflectingTarget_, prop, reflectingHandler_) {" +
        "      return function(fakeTarget_, ...otherArgs) {" +
        "        const realTarget = binder();" +
        "        if (prop == 'get' || prop == 'set')" +
        "          otherArgs[prop == 'get' ? 1 : 2] = realTarget;" +
        "        return Reflect[prop](realTarget, ...otherArgs);" +
        "      };" +
        "    }" +
        "  });" +
        "  return new Proxy(fakeTarget, reflectingHandler);" +
        "}"
    );
  }

  private void generateJsSymbols() {
    for (SymbolsFile symbolsFile : symbolsFiles) {
      symbolsFile.appendModule(this, scriptStatements);
    }
  }

  private void generateJsExports() {
    for (ExportsFile exportsFile : exportsFiles) {
      exportsFile.appendModule(this, scriptStatements);
    }
  }

  private void generatePostamble() {
    // Construct an expression that will calculate the file path for the WASM
    // module, relative to the current scriptStatements, and using the filename it has
    // on disk right now.
    
    CallExpression ce = new CallExpression(
      generateInstantiation(),
      ImmutableList.empty()
    );

    String wasmInstanceVar = "wasmInstance";
    String exportsVar = "es";
    String wrapperFunctionVar = "wrapExport";
    List<Statement> wasmInstanceStatements = new ArrayList<>();
    ModuleUtil.appendFragment(
      wasmInstanceStatements,
      "const es = wasmInstance.instance.exports;" +
        "let wasmEx;" +
        "function wrapExport(name) {" +
        "  const fn = es[name];" +
        "  return function(...args) {" +
        // Should not re-enter WebAssembly after something fails within!
        "    if (wasmEx !== undefined)" +
        "      throw new Error('WebAssembly previously threw: ' + wasmEx);" +
        "    try { return fn(...args); } catch (e) {" +
        "      wasmEx = e; throw e;" +
        "    }" +
        "  }" +
        "}"
    );
    wasmFile.appendExports(
      wasmInstanceStatements,
      exportsVar,
      wrapperFunctionVar
    );
    if (wasmFile.getNeedsExternalCallCtors()) {
      wasmInstanceStatements.add(new ExpressionStatement(
        new CallExpression(
          new ComputedMemberExpression(
            new LiteralStringExpression(WasmFile.CALL_CTORS_SYMBOL),
            new IdentifierExpression(exportsVar)
          ),
          ImmutableList.empty()
        )
      ));
    }
    ModuleUtil.appendFragment(
      wasmInstanceStatements,
      "return Object.freeze(" + EXPORTS_VAR + ");"
    );
    ce = new CallExpression(
      new StaticMemberExpression("then", ce),
      ImmutableList.of(
        new FunctionExpression(
          Maybe.empty(),
          false,
          new FormalParameters(
            ImmutableList.of(new BindingIdentifier(wasmInstanceVar)),
            Maybe.empty()
          ),
          new FunctionBody(
            ImmutableList.empty(),
            ImmutableList.from(wasmInstanceStatements)
          )
        )
      )
    );

    scriptStatements.add(new ReturnStatement(Maybe.of(ce)));
  }

  private Expression generateInstantiation() {
    String moduleVar = "wasmModule";
    List<Statement> instantiationStatements = new ArrayList<>();
    for (MemoryDefinition memoryDefinition : memoryDefinitions) {
      // __symbols['<MEMORY_NAME>'] = new WebAssembly.Memory(limit)
      WasmLimits limits = memoryDefinition.signature.limits;
      ImmutableList<ObjectProperty> limitProperties = ImmutableList.of(
        new DataProperty(
          new LiteralNumericExpression((double)limits.min),
          new StaticPropertyName("initial")
        )
      );
      if (limits.max.isPresent()) {
        limitProperties = ImmutableList.cons(
          new DataProperty(
            new LiteralNumericExpression((double)limits.max.get()),
            new StaticPropertyName("maximum")
          ),
          limitProperties
        );
      }
      instantiationStatements.add(new ExpressionStatement(
        new AssignmentExpression(
          new ComputedMemberExpression(
            new LiteralStringExpression(memoryDefinition.name),
            new IdentifierExpression(ModuleGenerator.SYMBOLS_VAR)
          ),
          new NewExpression(
            new StaticMemberExpression(
              "Memory",
              new IdentifierExpression("WebAssembly")
            ),
            ImmutableList.of(new ObjectExpression(limitProperties))
          )
        )
      ));
    }
    
    String wasmFileName = wasmFile.getPath().getFileName().toString();
    
    instantiationStatements.add(new ReturnStatement(
      Maybe.of(
        new CallExpression(
          new StaticMemberExpression(
            "instantiateStreaming",
            new IdentifierExpression("WebAssembly")
          ),
          ImmutableList.of(
            new CallExpression(
              new IdentifierExpression(FETCHER_VAR),
              ImmutableList.of(new LiteralStringExpression(wasmFileName))
            ),
            new ObjectExpression(
              ImmutableList.cons(
                new DataProperty(
                  new IdentifierExpression(SYMBOLS_VAR),
                  new StaticPropertyName(WasmFile.SYMBOLS_MODULE)
                ),
                wasmFile.getImports()
              )
            )
          )
        )
      )
    ));
    return new FunctionExpression(
      Maybe.empty(),
      false,
      new FormalParameters(
        ImmutableList.empty(),
        Maybe.empty()
      ),
      new FunctionBody(
        ImmutableList.empty(),
        ImmutableList.from(instantiationStatements)
      )
    );
  }

  private void generateWrapper() {
    List<ImportSpecifier> imports = RequirementsTable.INSTANCE.getImports();

    String factoryVar = "factory";

    List<Statement> umdFunctionBody = new ArrayList<>();
    ModuleUtil.appendFragment(
      umdFunctionBody,
      "let root, isNode = false;" +
        "if (typeof global === 'object' && " +
        "    global.toString() == '[object global]') {" +
        "  root = global; isNode = true;" +
        "} else if (typeof self === 'object' && self.Object !== undefined && " +
        "           self.Array !== undefined) {" +
        "  root = self;" +
        "} else throw new Error('Unable to detect global object');" +
        "const define = root.define;" +
        // Bind in all the bits that depend on document.currentScript, which
        // we have to capture *now*, rather than asynchronously inside the
        // module-factory itself.
        "const currentScript = isNode ? __dirname " +
        "                             : root.document.currentScript.src;" +
        "const fetcher = isNode ? function(name) {" +
        "  const fs = require('fs'), path = require('path');" +
        "  const buf = fs.readFileSync(path.join(currentScript, name));" +
        // Cargo-culting, apparently Node buffers can be re-used in a pool...
        "  const copy = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);\n" +
        "  return Promise.resolve(copy);" +
        "} : function(name) {" +
        "  const url = new root.URL(name,currentScript);" +
        "  return root.fetch(url.toString());" +
        "};" +
        "factory = factory.bind(null, root, fetcher);"
    );
    Statement umdAmdBranch = new ExpressionStatement(
      // define("<OUTPUT_NAME>", ["IMPORTS_FROM"], factory);
      new CallExpression(
        new IdentifierExpression("define"),
        ImmutableList.of(
          new LiteralStringExpression(moduleName),
          new ArrayExpression(
            ImmutableList.from(
              imports.stream()
                .map(i -> i.name.orJust(i.binding.name))
                .map(i ->
                  (SpreadElementExpression)new LiteralStringExpression(i))
                .map(Maybe::of)
                .collect(Collectors.toList())
            )
          ),
          new IdentifierExpression(factoryVar)
        )
      )
    );
    Statement umdNodeBranch = new ExpressionStatement(
      new AssignmentExpression(
        new StaticMemberExpression(
          "exports",
          new IdentifierExpression("module")
        ),
        new CallExpression(
          new IdentifierExpression("factory"),
          ImmutableList.from(
            imports.stream()
              .map(i -> i.name.orJust(i.binding.name))
              .map(i -> new CallExpression(
                new IdentifierExpression("require"),
                ImmutableList.of(new LiteralStringExpression(i))
              ))
              .collect(Collectors.toList())
          )
        )
      )
    );
    Statement umdFallbackBranch = new ExpressionStatement(
      // root["<OUTPUT_NAME>"] = factory(root["<IMPORTS_FROM>"]);
      new AssignmentExpression(
        new ComputedMemberExpression(
          new LiteralStringExpression(moduleName),
          new IdentifierExpression("root")
        ),
        new CallExpression(
          new IdentifierExpression(factoryVar),
          ImmutableList.from(
            imports.stream()
              .map(i -> i.name.orJust(i.binding.name))
              .map(i -> new ComputedMemberExpression(
                new LiteralStringExpression(i),
                new IdentifierExpression("root")
              ))
              .collect(Collectors.toList())
          )
        )
      )
    );
    umdFunctionBody.add(
      new IfStatement(
        ModuleUtil.parseFragmentExpression(
          "typeof define === 'function' && define.amd"
        ),
        umdAmdBranch,
        Maybe.of(
          new IfStatement(
            ModuleUtil.parseFragmentExpression(
              "typeof module === 'object' && module.exports"
            ),
            umdNodeBranch,
            Maybe.of(umdFallbackBranch)
          )
        )
      )
    );

    // function(__root, __fetcher, IMPORTS_AS) {
    //   <BODY>
    // }
    Expression factoryExpression = new FunctionExpression(
      Maybe.empty(),
      false,
      new FormalParameters(
        ImmutableList.cons(
          new BindingIdentifier(ROOT_VAR),
          ImmutableList.cons(
            new BindingIdentifier(FETCHER_VAR),
            ImmutableList.from(
              imports.stream()
                .map(i -> i.binding.name)
                .map(BindingIdentifier::new)
                .collect(Collectors.toList())
            )
          )
        ),
        Maybe.empty()
      ),
      new FunctionBody(
        ImmutableList.empty(),
        ImmutableList.from(scriptStatements)
      )
    );

    // (function(factory) { <UMD_FUNCTION_BODY> })(<FACTORY>)
    CallExpression umdRunner = new CallExpression(
      new FunctionExpression(
        Maybe.empty(),
        false,
        new FormalParameters(
          ImmutableList.of(new BindingIdentifier(factoryVar)),
          Maybe.empty()
        ),
        new FunctionBody(
          ImmutableList.empty(),
          ImmutableList.from(umdFunctionBody)
        )
      ),
      ImmutableList.of(factoryExpression)
    );

    scriptStatements = Collections.singletonList(new ExpressionStatement(umdRunner));
  }

  private Script generateScript() {
    return new Script(
      ImmutableList.from(new Directive("use strict")),
      ImmutableList.from(scriptStatements)
    );
  }

  public void appendImports(
    List<Statement> statementsOut,
    List<ImportSpecifier> symbolImports,
    List<Import> requirementsImports
  ) {
    List<VariableDeclarator> requirementsDeclarators = new ArrayList<>();
    for (Import i : requirementsImports) {
      RequirementsTable.Requirement r =
        RequirementsTable.INSTANCE.get(i.moduleSpecifier);
      if (i.defaultBinding.isJust() &&
        !i.defaultBinding.fromJust().name.equals(r.variableName)) {
        requirementsDeclarators.add(
          new VariableDeclarator(
            new BindingIdentifier(i.defaultBinding.fromJust().name),
            Maybe.of(new IdentifierExpression(r.variableName))
          )
        );
      }
      for (ImportSpecifier is : i.namedImports) {
        requirementsDeclarators.add(
          new VariableDeclarator(
            new BindingIdentifier(is.binding.name),
            Maybe.of(new ComputedMemberExpression(
              new LiteralStringExpression(is.name.orJust(is.binding.name)),
              new IdentifierExpression(r.variableName)
            ))
          )
        );
      }
    }
    if (!requirementsDeclarators.isEmpty()) {
      statementsOut.add(new VariableDeclarationStatement(
        // const <NAME> = <REQ>
        new VariableDeclaration(
          VariableDeclarationKind.Const,
          ImmutableList.from(requirementsDeclarators)
        )
      ));
    }

    if (!symbolImports.isEmpty()) {
      statementsOut.add(new VariableDeclarationStatement(
        // let <NAME>, ...
        new VariableDeclaration(
          VariableDeclarationKind.Let,
          ImmutableList.from(
            symbolImports.stream()
              .map(is -> new VariableDeclarator(
                new BindingIdentifier(is.binding.name),
                Maybe.empty()
              ))
              .collect(Collectors.toList())
          )
        )
      ));
    }
    for (ImportSpecifier is : symbolImports) {
      String symbolName = is.name.orJust(is.binding.name);
      SymbolTable.Symbol symbol = SymbolTable.INSTANCE.getSymbol(symbolName);
      boolean isCallable = symbol.getDescriptor() == null ||
        symbol.getDescriptor().type == WasmObjectType.FUNCTION;

      // <NAME> = __lateBind(() => (<NAME> = <IMPORTED_NAME>))
      statementsOut.add(new ExpressionStatement(
        new AssignmentExpression(
          new BindingIdentifier(is.binding.name),
          ModuleUtil.generateLateBinding(
            new BindingIdentifier(is.binding.name),
            new ComputedMemberExpression(
              new LiteralStringExpression(symbolName),
              new IdentifierExpression(ModuleGenerator.SYMBOLS_VAR)
            ),
            isCallable
          )
        )
      ));
    }
  }

  public void appendExports(
    List<Statement> statementsOut,
    List<ExportSpecifier> exports,
    IdentifierExpression exportIdentifier
  ) {
    for (ExportSpecifier es : exports) {
      // __exports['<EXPORTED_NAME>'] = <NAME>
      statementsOut.add(new ExpressionStatement(
        new AssignmentExpression(
          new ComputedMemberExpression(
            new LiteralStringExpression(es.exportedName),
            exportIdentifier
          ),
          new IdentifierExpression(es.name.orJust(es.exportedName))
        )
      ));
    }
  }


  /*
   * Returns the symbol name, with a random suffix added.  This is to make it
   * impossible for user code to reference the symbol directly - since its name
   * is random and changes every time.
   *
   * We do unavoidable pollute the module's namespace with some variable names,
   * and doing this ensures that callers won't accidentally call those names.
   */
  static public String mungeSymbol(String s) {
    long budge = new SecureRandom().nextLong() % (1L << 48);
    return s + '_' + Long.toHexString(budge);
  }

  /*
   * Returns the symbol name, unbudged (to show that this is a conscious
   * decision).  These symbols are part of the API js-ld exposes to its modules.
   */
  static public String dontMungeSymbol(String s) {
    return s;
  }

}
