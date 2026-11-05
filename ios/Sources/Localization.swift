import Foundation

/// Texto traducido según el idioma del iPhone (inglés o español; inglés si el
/// idioma del móvil no es ninguno de los dos). La clave es el texto en español.
func L(_ clave: String) -> String {
    NSLocalizedString(clave, comment: "")
}

/// Igual, con valores que se insertan en los %@ / %ld del texto.
func L(_ clave: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(clave, comment: ""), arguments: args)
}
