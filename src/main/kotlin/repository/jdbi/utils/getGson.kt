package repository.jdbi.utils
import com.google.gson.Gson
import com.google.gson.GsonBuilder

fun gson(): Gson =
    GsonBuilder().setPrettyPrinting().create()
